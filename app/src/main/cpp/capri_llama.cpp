// Capri's brain on the phone: a thin JNI bridge to llama.cpp (b11932, same as the desktop build).
// load() a GGUF, generate() a chat reply with the model's own template (thinking off), streaming pieces to Kotlin.
// The prompt of the last turn stays in the KV cache, so the next turn only processes what's new.

#include <jni.h>
#include <android/log.h>

#include <mutex>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "capri_llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

std::string g_error;

struct Brain {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    common_chat_templates_ptr tmpls;
    mtmd_context * eyes = nullptr;    // her vision (the model's mmproj), if loaded
    std::vector<llama_token> cached;  // tokens currently in the KV cache (sequence 0)
    int n_ctx = 2048;
    int n_batch = 512;
};

void on_log(ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

std::string from_java(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// UTF-8 -> a Java string. NewStringUTF only takes "modified UTF-8" (no 4-byte emoji), so decode by hand.
jstring to_java(JNIEnv * env, const std::string & s) {
    std::u16string u;
    u.reserve(s.size());
    for (size_t i = 0; i < s.size();) {
        auto c = (unsigned char) s[i];
        uint32_t cp;
        int n;
        if (c < 0x80) { cp = c; n = 1; }
        else if ((c >> 5) == 0x6) { cp = c & 0x1F; n = 2; }
        else if ((c >> 4) == 0xE) { cp = c & 0x0F; n = 3; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07; n = 4; }
        else { i++; continue; }  // stray byte
        if (i + n > s.size()) break;
        for (int k = 1; k < n; k++) cp = (cp << 6) | ((unsigned char) s[i + k] & 0x3F);
        i += n;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            u.push_back((char16_t) (0xD800 + (cp >> 10)));
            u.push_back((char16_t) (0xDC00 + (cp & 0x3FF)));
        } else {
            u.push_back((char16_t) cp);
        }
    }
    return env->NewString((const jchar *) u.data(), (jsize) u.size());
}

// How many leading bytes of s are whole UTF-8 characters (a token can end mid-character).
size_t complete_utf8(const std::string & s) {
    size_t i = s.size();
    int back = 0;
    while (i > 0 && back < 4) {
        auto c = (unsigned char) s[i - 1];
        if ((c & 0xC0) != 0x80) {  // a lead byte (or ASCII)
            int need = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
            return (back + 1 >= need) ? s.size() : i - 1;
        }
        i--;
        back++;
    }
    return s.size();
}

bool decode(Brain * b, const llama_token * toks, int n) {
    for (int i = 0; i < n; i += b->n_batch) {
        int k = std::min(b->n_batch, n - i);
        if (llama_decode(b->ctx, llama_batch_get_one(const_cast<llama_token *>(toks + i), k)) != 0) return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_capri_mobile_Llama_load(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    static std::once_flag once;
    std::call_once(once, [] {
        llama_log_set(on_log, nullptr);
        llama_backend_init();
    });
    std::string path = from_java(env, jpath);
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;  // CPU: works on every phone
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) {
        g_error = "this llama.cpp can't read that file (unsupported model type, or a broken download)";
        return 0;
    }
    auto cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        g_error = "not enough memory for this brain";
        return 0;
    }
    auto * b = new Brain();
    b->model = model;
    b->ctx = ctx;
    b->vocab = llama_model_get_vocab(model);
    b->n_ctx = n_ctx;
    try {
        b->tmpls = common_chat_templates_init(model, "");
    } catch (const std::exception & e) {
        LOGW("chat template: %s (using a basic one)", e.what());
        b->tmpls = common_chat_templates_init(model, "chatml");
    }
    LOGI("loaded %s, ctx %d, %d threads", path.c_str(), n_ctx, n_threads);
    return (jlong) b;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_capri_mobile_Llama_error(JNIEnv * env, jobject) {
    return to_java(env, g_error);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_capri_mobile_Llama_generate(JNIEnv * env, jobject, jlong handle, jobjectArray roles, jobjectArray contents,
                                       jint max_tokens, jfloat temperature, jobject piece) {
    auto * b = (Brain *) handle;
    if (!b) return to_java(env, "");

    common_chat_templates_inputs in;
    jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; i++) {
        common_chat_msg m;
        m.role = from_java(env, (jstring) env->GetObjectArrayElement(roles, i));
        m.content = from_java(env, (jstring) env->GetObjectArrayElement(contents, i));
        in.messages.push_back(m);
    }
    in.add_generation_prompt = true;
    in.use_jinja = true;
    in.enable_thinking = false;  // she answers straight away, no hidden thinking

    common_chat_params params;
    try {
        params = common_chat_templates_apply(b->tmpls.get(), in);
    } catch (const std::exception & e) {
        LOGW("template failed (%s), trying the legacy one", e.what());
        try {
            in.use_jinja = false;
            params = common_chat_templates_apply(b->tmpls.get(), in);
        } catch (const std::exception & e2) {
            g_error = std::string("chat template: ") + e2.what();
            return to_java(env, "");
        }
    }

    // template text may hold special tokens; your words never do
    std::vector<llama_token> toks;
    for (const auto & part : params.prompt.parts) {
        auto t = common_tokenize(b->vocab, part.text, false, !part.is_input);
        toks.insert(toks.end(), t.begin(), t.end());
    }
    if (llama_vocab_get_add_bos(b->vocab) && (toks.empty() || toks[0] != llama_vocab_bos(b->vocab))) {
        toks.insert(toks.begin(), llama_vocab_bos(b->vocab));
    }
    if ((int) toks.size() + max_tokens > b->n_ctx) {  // too long: keep the end (her character sheet is lost, rare)
        g_error = "conversation too long";
        toks.erase(toks.begin(), toks.end() - std::max(16, b->n_ctx - max_tokens - 8));
    }

    // reuse what's already in the cache, process only the new part
    size_t keep = 0;
    while (keep < b->cached.size() && keep < toks.size() && b->cached[keep] == toks[keep]) keep++;
    if (keep == toks.size() && keep > 0) keep--;  // the last prompt token must be decoded to get logits
    llama_memory_seq_rm(llama_get_memory(b->ctx), 0, (llama_pos) keep, -1);
    b->cached.resize(keep);
    if (!decode(b, toks.data() + keep, (int) (toks.size() - keep))) {
        llama_memory_clear(llama_get_memory(b->ctx), true);
        b->cached.clear();
        g_error = "couldn't process the prompt";
        return to_java(env, "");
    }
    b->cached.insert(b->cached.end(), toks.begin() + (long) keep, toks.end());

    auto * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(b->vocab), 64, 1.08f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.92f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass cls = env->GetObjectClass(piece);
    jmethodID on_piece = env->GetMethodID(cls, "onPiece", "(Ljava/lang/String;)Z");

    std::string reply, pending;
    for (int i = 0; i < max_tokens; i++) {
        llama_token t = llama_sampler_sample(smpl, b->ctx, -1);
        if (llama_vocab_is_eog(b->vocab, t)) break;
        pending += common_token_to_piece(b->vocab, t, false);
        size_t ok = complete_utf8(pending);
        bool go = true;
        if (ok > 0) {
            std::string out = pending.substr(0, ok);
            pending.erase(0, ok);
            reply += out;
            jstring js = to_java(env, out);
            go = env->CallBooleanMethod(piece, on_piece, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) { env->ExceptionClear(); go = false; }
        }
        if (llama_decode(b->ctx, llama_batch_get_one(&t, 1)) != 0) break;
        b->cached.push_back(t);
        if (!go) break;
    }
    llama_sampler_free(smpl);
    return to_java(env, reply);
}

// -- her eyes -----------------------------------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_capri_mobile_Llama_loadEyes(JNIEnv * env, jobject, jlong handle, jstring jpath, jint n_threads) {
    auto * b = (Brain *) handle;
    if (!b) return JNI_FALSE;
    if (b->eyes) { mtmd_free(b->eyes); b->eyes = nullptr; }
    auto p = mtmd_context_params_default();
    p.use_gpu = false;
    p.n_threads = n_threads;
    p.print_timings = false;
    p.warmup = false;
    p.image_max_tokens = 256;  // a phone CPU: fewer image tokens = she looks in seconds, not minutes
    std::string path = from_java(env, jpath);
    b->eyes = mtmd_init_from_file(path.c_str(), b->model, p);
    if (!b->eyes || !mtmd_support_vision(b->eyes)) {
        if (b->eyes) mtmd_free(b->eyes);
        b->eyes = nullptr;
        g_error = "those eyes don't fit this brain";
        return JNI_FALSE;
    }
    LOGI("eyes loaded: %s", path.c_str());
    return JNI_TRUE;
}

// Like generate(), but the last user message has a picture: "{IMAGE}" in its text marks where it goes.
extern "C" JNIEXPORT jstring JNICALL
Java_com_capri_mobile_Llama_look(JNIEnv * env, jobject, jlong handle, jbyteArray rgb, jint w, jint h,
                                   jobjectArray roles, jobjectArray contents, jint max_tokens, jfloat temperature,
                                   jobject piece) {
    auto * b = (Brain *) handle;
    if (!b || !b->eyes) return to_java(env, "");

    common_chat_templates_inputs in;
    jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; i++) {
        common_chat_msg m;
        m.role = from_java(env, (jstring) env->GetObjectArrayElement(roles, i));
        m.content = from_java(env, (jstring) env->GetObjectArrayElement(contents, i));
        in.messages.push_back(m);
    }
    in.add_generation_prompt = true;
    in.use_jinja = true;
    in.enable_thinking = false;
    std::string prompt;
    try {
        prompt = common_chat_templates_apply(b->tmpls.get(), in).prompt.str();
    } catch (const std::exception & e) {
        g_error = std::string("chat template: ") + e.what();
        return to_java(env, "");
    }
    const std::string tag = "{IMAGE}";
    auto at = prompt.find(tag);
    if (at == std::string::npos) prompt += mtmd_default_marker();
    else prompt.replace(at, tag.size(), mtmd_default_marker());

    jsize len = env->GetArrayLength(rgb);
    std::vector<unsigned char> pixels((size_t) len);
    env->GetByteArrayRegion(rgb, 0, len, (jbyte *) pixels.data());
    mtmd_bitmap * bmp = mtmd_bitmap_init((uint32_t) w, (uint32_t) h, pixels.data());
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    mtmd_input_text text{prompt.c_str(), prompt.size(), true, true};
    const mtmd_bitmap * bmps[] = {bmp};
    int32_t res = mtmd_tokenize(b->eyes, chunks, &text, bmps, 1);
    mtmd_bitmap_free(bmp);
    if (res != 0) {
        mtmd_input_chunks_free(chunks);
        g_error = "couldn't read the picture";
        return to_java(env, "");
    }

    // a picture never matches the cache: start the sequence over (the next chat turn rebuilds its prefix)
    llama_memory_clear(llama_get_memory(b->ctx), true);
    b->cached.clear();
    llama_pos n_past = 0;
    if (mtmd_helper_eval_chunks(b->eyes, b->ctx, chunks, 0, 0, b->n_batch, true, &n_past) != 0) {
        mtmd_input_chunks_free(chunks);
        llama_memory_clear(llama_get_memory(b->ctx), true);
        g_error = "couldn't look at it (out of memory?)";
        return to_java(env, "");
    }
    mtmd_input_chunks_free(chunks);

    auto * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(b->vocab), 64, 1.08f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.92f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    jclass cls = env->GetObjectClass(piece);
    jmethodID on_piece = env->GetMethodID(cls, "onPiece", "(Ljava/lang/String;)Z");
    std::string reply, pending;
    for (int i = 0; i < max_tokens; i++) {
        llama_token t = llama_sampler_sample(smpl, b->ctx, -1);
        if (llama_vocab_is_eog(b->vocab, t)) break;
        pending += common_token_to_piece(b->vocab, t, false);
        size_t ok = complete_utf8(pending);
        bool go = true;
        if (ok > 0) {
            std::string out = pending.substr(0, ok);
            pending.erase(0, ok);
            reply += out;
            jstring js = to_java(env, out);
            go = env->CallBooleanMethod(piece, on_piece, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) { env->ExceptionClear(); go = false; }
        }
        if (llama_decode(b->ctx, llama_batch_get_one(&t, 1)) != 0) break;
        if (!go) break;
    }
    llama_sampler_free(smpl);
    llama_memory_clear(llama_get_memory(b->ctx), true);  // keep the cache honest: nothing of this is reused
    b->cached.clear();
    return to_java(env, reply);
}

extern "C" JNIEXPORT void JNICALL
Java_com_capri_mobile_Llama_free(JNIEnv *, jobject, jlong handle) {
    auto * b = (Brain *) handle;
    if (!b) return;
    if (b->eyes) mtmd_free(b->eyes);
    if (b->ctx) llama_free(b->ctx);
    if (b->model) llama_model_free(b->model);
    delete b;
}
