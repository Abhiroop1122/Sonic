Onboarding pictures, loaded at runtime (ui/OnboardingArt.kt, rememberOnboardingPictures).
Each name may be .webp (preferred), .png or .jpg. A missing file is fine: the screen keeps its
drawn layout.

Step pictures (square, white background, 1024 x 1024), shown between the big step number and the copy:
  step-02   02 · offline (bring your own records)
  step-03   03 · where should downloads go?
  step-04   04 · share a folder?

Sign-in collage covers (square, 1024 x 1024), 3 x 2 in this order, left to right, top row first:
  cover-1   night drive, purple
  cover-2   teal
  cover-3   cream
  cover-4   blue
  cover-5   charcoal
  cover-6   pink
Any cover that's missing falls back to the drawn sleeve in its place.

Intro (the first screen: a sleeve slides in, its record flies to the deck):
  intro-sleeve   the sleeve, square, edge to edge (no background), 1024 x 1024: the SONIC
                 sleeve, the same art as the launcher icon
  intro-disc     the record from straight above, black vinyl with a printed paper label, on a
                 TRANSPARENT background (PNG), the record touching all four edges, 1024 x 1024.
                 Its label is 39 % of the record's diameter, centred (Intro.kt LABEL; at least 35 %
                 so it covers the deck's own): Sonic lays that same label over the deck's once the
                 record lands.
Missing either, the intro uses its built-in sleeve and disc.

Dark theme (Settings › Flags › theme, or the phone's dark mode). Each picture may have a "-dark"
twin, used in the dark theme; missing, the light one shows:
  step-02-dark … step-05-dark   the step pictures redrawn on true black (#000000), same
                                composition, square 1024 x 1024
  intro-sleeve-dark             the SONIC sleeve on its black card (the launcher icon's)

Intro deck renders for the other colourways (Settings › Flags › deck), used whatever the theme:
  deck-{empty,loaded,playing,arm}-dark   the dark RG-1
  deck-{empty,loaded,playing,arm}-mono   the mono white RG-1
  deck-{empty,loaded,playing,arm}-neon   the neon RG-1 (the dark room's own; captured with its lights out)
These are rendered from the room's own 3D deck by a debug build (ui/deck/DeckCapture.kt), so the
intro lands on exactly the deck the room shows: pick the deck in Flags, use the three-quarter view,
run
    adb shell am start -n app.sonic.dev/app.runout.MainActivity --ez capture_intro_deck true
pull Android/data/app.sonic.dev/files/deck-capture/*.png and convert each with
    cwebp -q 86 -alpha_q 90 deck-<state>.png -o deck-<state>-<dark|mono>.webp
The intro's positions for them (IntroGeometry.Captured in Intro.kt) come from the same run's log.
White / teal uses the built-in res/drawable-nodpi/onboarding_deck_*.
