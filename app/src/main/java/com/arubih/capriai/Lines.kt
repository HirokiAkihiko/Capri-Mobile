package com.arubih.capriai

import kotlin.random.Random

/** What she says when she has no brain yet: short, Night City, never the same line twice in a row. */
object Lines {
    val headPoke = listOf("Hey! Watch the optics.", "That's my head, gonk.", "Poke me again. I dare you.",
        "Rude. Very rude.", "You trying to reboot me?", "Ow. Chrome's still chrome, choom.")
    val bodyPoke = listOf("Hm?", "What's up, choom?", "Yeah, yeah, I'm here.", "Need something?", "Boop yourself.",
        "Careful, I bite.")
    val doubleTap = listOf("Heh. Again.", "Okay, that one was cute.", "Double tap? Smooth.", "Nova.", "Hehe.")
    val mad = listOf("THAT'S IT. I'm out.", "Keep poking, see what happens. Oh wait.", "Flatline yourself, choom.",
        "Nope. Bye.", "Gonk. Absolute gonk.")
    val dizzy = listOf("Whoa... there's like five of you.", "Stop shaking me, I'm gonna glitch.",
        "My gyros... my gyros...", "Room's spinning, choom.", "Who turned on the earthquake?")
    val thrown = listOf("WHEEE!", "Not again!", "I'm flying, choom!", "Put me DOWN!")
    val peek = listOf("Peekaboo.", "You can't see me.", "I'm not here.", "Just watching.")
    val popOut = listOf("Fine, fine. I'm out.", "Found me.", "Ta-da.")
    val charger = listOf("Ooh, juice. Jacked in.", "Mmm, eddies for my battery.", "Charging up. Feels preem.",
        "Power. Sweet, sweet power.")
    val unplugged = listOf("Hey! I was eating that.", "Unplugged? Cold.", "Running on my own now.")
    val lowBattery = listOf("Running on fumes, choom.", "Battery's low. I'm getting sleepy.",
        "Plug me in before I flatline.", "Feeling... kinda... drained.")
    val napLow = listOf("Nap time. Wake me when you find a charger.", "Saving power. Night, choom.")
    val night = listOf("It's late, choom. Go to sleep.", "Midnight run? Respect.", "Even netrunners sleep sometimes.",
        "Night City never sleeps. You should though.")
    val morning = listOf("Morning, choom. Rise and grind.", "Up already? Preem.", "New day, new gig.",
        "Coffee first, then chaos.")
    val afternoon = listOf("Afternoon, choom. Miss me?", "Back again? Knew it.", "What's the plan, choom?")
    val evening = listOf("Evening, choom. Long day?", "Night's young. What we doing?", "Welcome back, choom.")
    val lateNight = listOf("You should be asleep, gonk.", "3AM scrolling? Same.", "Couldn't sleep either, huh?")
    val sleepy = listOf("mm... five more minutes...", "zzz... huh? Oh. Hi.", "...still napping, choom.")
    val tiktok = listOf("TikTok again? Find me in there.", "One more scroll, huh? Liar.", "Show me the good ones.",
        "Doomscroll mode: on.")
    val youtube = listOf("What we watching?", "Put on something nova.", "YouTube? Pick a good one, choom.")
    val spotify = listOf("Ooh, music. Play something preem.", "DJ time? I'll judge.", "Make it a banger.")
    val discord = listOf("Say hi to the chooms for me.", "Discord? Tell 'em Capri says hi.", "Who we talking to?")
    val game = listOf("Game time? Don't flatline.", "Let's play, choom.", "Win this one for me.",
        "Go get 'em, edgerunner.")
    val instagram = listOf("Insta? Post me next time.", "Scrolling the 'gram, huh?")
    val gift = listOf("Got something for you.", "Found this. It's yours.", "Here. Don't lose it.",
        "Little gift. Don't make it weird.")
    val collected = listOf("Nova. Added to your stash.", "Good catch, choom.", "Yoink. It's yours.")
    val woke = listOf("Mm. I'm up, I'm up.", "Back online.", "What'd I miss?")

    private val last = HashMap<List<String>, String>()

    fun pick(lines: List<String>): String {
        var s = lines[Random.nextInt(lines.size)]
        if (lines.size > 1 && s == last[lines]) s = lines[(lines.indexOf(s) + 1) % lines.size]
        last[lines] = s
        return s
    }
}
