package app.runout.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.runout.R
import app.runout.soulseek.SoulseekConnection
import app.runout.soulseek.SoulseekPolicy

/**
 * Onboarding steps. The numbers are what is saved as the resume point, unchanged from the earlier
 * setup so an interrupted onboarding picks up in the right place; a step added later takes the next
 * free number, wherever it sits in the order.
 */
internal enum class Step(val saved: Int) { INTRO(0), LOGIN(1), DOWNLOADS(2), SHARE(3), RHYTHM(4), WELCOME(5), OFFLINE(6), DROPS(7) }

private fun stepFor(saved: Int, intro: Boolean): Step = when (saved) {
    0 -> if (intro) Step.INTRO else Step.LOGIN
    else -> Step.entries.firstOrNull { it.saved == saved } ?: Step.LOGIN
}

/**
 * Onboarding, on its own full screen over the room (TE · Onboarding boards): the intro, the
 * Soulseek sign-in under its record collage, then one decision per screen under a giant dotted
 * step number, with everything near the bottom for a thumb. Choices move on by themselves; there
 * are no Continue buttons. It ends by greeting you beside the deck and dropping you into the room.
 */
@Composable
internal fun SetupScreen(
    state: RoomState,
    actions: RoomActions,
    initialStep: Int,
    /** Show the intro animation before the sign-in (first launch only). */
    intro: Boolean = false,
    /** Leave without finishing, when setup was opened again from the room. */
    onClose: (() -> Unit)? = null,
    onFinish: () -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(stepFor(initialStep.coerceIn(0, 7), intro)) }
    fun move(next: Step) { step = next; if (next != Step.INTRO) actions.setOnboardingStep(next.saved) }
    fun back() {
        when (step) {
            Step.OFFLINE, Step.DOWNLOADS -> move(Step.LOGIN)
            Step.SHARE -> move(Step.DOWNLOADS)
            Step.DROPS -> move(Step.SHARE)
            Step.RHYTHM -> move(Step.DROPS)
            else -> onClose?.invoke()
        }
    }
    // On the first run, Back on the sign-in leaves the app without finishing onboarding.
    BackHandler(enabled = step !in setOf(Step.INTRO, Step.LOGIN, Step.WELCOME) || onClose != null) { back() }

    val deckSpot = LocalDeckSpot.current
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawPaper(deckSpot) }
            .testTag("screen:setup"),
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = { (fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 12 }) togetherWith fadeOut(tween(180)) },
            label = "onboarding",
        ) { shown ->
            // Each step is its own subtree, so TalkBack (and test tools) never see the previous step's text.
            key(shown) {
                when (shown) {
                    Step.INTRO -> RunoutIntro(onFinished = { move(Step.LOGIN) }, deck = state.look.deckFor(Room.dark))
                    Step.LOGIN -> LoginStep(state, actions, onClose, onConnected = { move(Step.DOWNLOADS) }, onOffline = { actions.disconnectSoulseek(); move(Step.OFFLINE) })
                    Step.OFFLINE -> OfflineStep(state, actions, onDone = { move(Step.WELCOME) }, onBack = { back() })
                    Step.DOWNLOADS -> DownloadsStep(state, actions, onDone = { move(Step.SHARE) }, onBack = { back() })
                    Step.SHARE -> ShareStep(state, actions, onDone = { move(Step.DROPS) }, onBack = { back() })
                    Step.DROPS -> DropsStep(onDone = { move(Step.RHYTHM) }, onBack = { back() })
                    Step.RHYTHM -> RhythmStep(state, actions, onDone = { move(Step.WELCOME) }, onBack = { back() })
                    Step.WELCOME -> WelcomeStep(state.soulseekSession.username?.takeIf { state.soulseekSession.hasAccount }, state.look.deckFor(Room.dark), onFinish)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pieces

/**
 * A full-height column that still scrolls when the screen is short: children can use weights to
 * push the actions to the foot, and nothing is cut off on a small phone.
 */
@Composable
private fun FootColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight),
            content = content,
        )
    }
}

/**
 * The key of a step: 16 × 22 in, a 12/400 label, black (white text), or #B2B2B2 while it can't
 * be pressed yet. An optional 16 dp icon sits 11 dp before the label.
 */
@Composable
private fun StepKey(
    text: String,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit)? = null,
) {
    val tick = rememberTick()
    Row(
        modifier
            .background(if (enabled) Room.Ink else Room.Faint)
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); onClick() }
            .testTag(tag)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Canvas(Modifier.size(16.dp)) { icon() }
        BasicText(text, style = Room.Text.copy(color = Room.OnInk), maxLines = 1)
    }
}

/** A plain text action (12/400 ink unless told otherwise), with a full-height touch target. */
@Composable
private fun TextKey(text: String, onClick: () -> Unit, tag: String, modifier: Modifier = Modifier, style: TextStyle = Room.Text) {
    val tick = rememberTick()
    BasicText(
        text,
        style = style,
        modifier = modifier
            .testTag(tag)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); onClick() }
            .touchHeight(),
    )
}

/** A 10/400 grey kicker, the 33/700 title and the 12/400 body of a step (TE · Onboarding · Offline). */
@Composable
private fun StepCopy(label: String, title: String, body: String) {
    BasicText(label, style = Room.LabelQuiet)
    BasicText(title, style = Room.Display.copy(fontSize = 33.sp, lineHeight = 36.sp), modifier = Modifier.fillMaxWidth().semantics { heading() })
    BasicText(body, style = OnboardingBody, modifier = Modifier.fillMaxWidth())
}

/**
 * Steps 02–06: "← back" and six progress squares along the top (22 in), the step number set at
 * 132/700 under them, the copy at the foot of the content, then the actions, 22 / 22 / 33 in.
 * [picture] (a file in assets/onboarding) sits in the space between the number and the copy when
 * it's there; it shrinks to fit the screen, and gives way altogether on a short one, never the type.
 * When the screen is too short for everything, the space closes and the page scrolls.
 */
@Composable
private fun StepPage(
    number: Int,
    onBack: () -> Unit,
    copy: @Composable ColumnScope.() -> Unit,
    picture: String? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val art = if (picture != null) rememberOnboardingPicture(picture, STEP_PICTURE_MAX) else null
    FootColumn {
        // 22 in on the board; the 48 dp touch height of "← back" takes up most of that.
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextKey("← back", onBack, "setup-back", Modifier.semantics { contentDescription = "Back" })
            Spacer(Modifier.weight(1f))
            Pips(number)
        }
        BasicText(
            "%02d".format(number),
            style = Room.Display.copy(fontSize = 132.sp, lineHeight = 119.sp),
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 32.dp).clearAndSetSemantics { },
        )
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterStart) {
            // 22 under the number; the copy's own 22 keeps it off the kicker.
            val side = minOf(maxHeight - 24.dp, maxWidth, STEP_PICTURE_MAX)
            if (art != null && side >= STEP_PICTURE_MIN) {
                val shown = remember(art) { Animatable(0f) }
                LaunchedEffect(art) { shown.animateTo(1f, tween(260)) }
                Image(
                    art,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .padding(top = 24.dp)
                        .size(side)
                        .graphicsLayer { alpha = shown.value }
                        .testTag("setup-picture"),
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = copy,
        )
        if (actions != null) ActionColumn(Modifier.padding(top = 24.dp), actions)
    }
}

/** How many numbered steps the signed-in path has, from the sign-in (01) to the rhythm (06). */
private const val STEPS = 6

/** The step pictures: up to 240 dp square, and left out below 96 dp rather than shrinking the type. */
private val STEP_PICTURE_MAX = 240.dp
private val STEP_PICTURE_MIN = 96.dp

/**
 * The actions at the foot, 22 / 33 in and 16 apart on the board. The text links below the key
 * carry a 48 dp touch height, which already gives them that space, so it isn't added again.
 */
@Composable
private fun ActionColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = LINK_FOOT),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** 33 dp under the last link's text, less the half of its touch height that sits under it. */
private val LINK_FOOT = 16.dp

/** "what's this?" in blue, its text resting on the field's rule, with a 48 dp touch height. */
@Composable
private fun HelpLink(onClick: () -> Unit) {
    val tick = rememberTick()
    Box(
        Modifier
            .testTag("soulseek-id-info")
            .semantics { contentDescription = "How Soulseek IDs work" }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); onClick() }
            .height(48.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        BasicText("what's this?", style = Room.Label.copy(color = Room.Accent), modifier = Modifier.padding(bottom = 1.dp))
    }
}

/** Six 8 dp squares, 4 apart: filled up to this step, outlined after it. */
@Composable
private fun Pips(number: Int) {
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = "Step $number of $STEPS" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(STEPS) { i ->
            Box(Modifier.size(8.dp).then(if (i < number) Modifier.background(Room.Ink) else Modifier.border(1.dp, Room.Ink)))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sign-in

@Composable
private fun LoginStep(state: RoomState, actions: RoomActions, onClose: (() -> Unit)?, onConnected: () -> Unit, onOffline: () -> Unit) {
    val session = state.soulseekSession
    var id by rememberSaveable(state.soulseekSavedUsername) { mutableStateOf(state.soulseekSavedUsername.orEmpty()) }
    // Passwords never go into saved instance state.
    var password by remember { mutableStateOf("") }
    var info by remember { mutableStateOf(false) }
    val busy = session.state == SoulseekConnection.CONNECTING || session.state == SoulseekConnection.RECONNECTING
    // Move on only when a sign-in made here succeeds, never just because an account is already
    // connected (Back from the next step must be able to land here).
    var wasConnected by remember { mutableStateOf(session.connected) }
    LaunchedEffect(session.connected) {
        if (session.connected && !wasConnected) onConnected()
        wasConnected = session.connected
    }
    val canConnect = !busy && SoulseekPolicy.validUsername(id.trim()) && password.isNotEmpty()
    val connect = {
        actions.clearSoulseekError()
        // Always remembered: this is an app on your phone, you stay signed in (Android Keystore).
        actions.signInSoulseek(id.trim(), password, true)
        password = ""
    }
    // With the keyboard up there is no room for the collage and the line: they'd slide up under
    // each other. The collage folds away and the wordmark shrinks to 22.
    @OptIn(ExperimentalLayoutApi::class)
    val typing = WindowInsets.isImeVisible
    val wordmark by animateFloatAsState(if (typing) 22f else 44f, label = "wordmark")

    @Composable
    fun Connect(modifier: Modifier) {
        if (session.connected) StepKey("continue", onConnected, "setup-continue", modifier)
        else StepKey(if (busy) "connecting…" else "connect", connect, "submit-soulseek", modifier, enabled = canConnect)
    }

    Box(Modifier.fillMaxSize()) {
        // The collage stays on a short screen and in landscape too: the page scrolls instead, the
        // fields and the key sized as they are, with the key below them.
        FootColumn {
            AnimatedVisibility(!typing, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                RecordCollage()
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Wordmark(wordmark.sp)
                if (!typing) BasicText("every record you own. one room to play them.", style = OnboardingBody, modifier = Modifier.fillMaxWidth())
                if (session.connected) {
                    BasicText("signed in to soulseek as ${session.username}.", style = OnboardingBody)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                        LineField(
                            id, { id = it.take(30) }, label = "soulseek id", placeholder = "your name", name = "Soulseek ID",
                            quietRule = typing, imeAction = ImeAction.Next, modifier = Modifier.weight(1f),
                        )
                        HelpLink { info = true }
                    }
                    LineField(
                        password, { password = it.take(128) }, label = "password", placeholder = "••••••••", name = "Password",
                        secret = true, quietRule = typing, imeAction = ImeAction.Done, onAction = { if (canConnect) connect() },
                    )
                    if (id.isNotEmpty() && !SoulseekPolicy.validUsername(id.trim())) {
                        BasicText("use 1–30 printable characters.", style = Room.Label.copy(color = Room.Danger))
                    }
                    (session.error ?: state.soulseekError)?.let {
                        BasicText(it, style = Room.Label.copy(color = Room.Danger, lineHeight = 15.sp), modifier = Modifier.testTag("soulseek-error"))
                    }
                }
                if (typing) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Connect(Modifier)
                        Spacer(Modifier.weight(1f))
                        TextKey("skip, listen offline →", onOffline, "setup-offline", style = Room.Label)
                    }
                }
            }
            // Pushes the actions to the foot when there's room; closes up when there isn't.
            Spacer(Modifier.weight(1f))
            if (!typing) {
                ActionColumn {
                    Connect(Modifier.fillMaxWidth())
                    if (!session.connected && state.soulseekSavedUsername != null && !busy) {
                        TextKey("sign in again as ${state.soulseekSavedUsername} →", actions::signInSavedSoulseek, "setup-saved", style = Room.Label)
                    }
                    TextKey("skip, listen offline →", onOffline, "setup-offline")
                }
            }
        }
        // Opened again from the room: "close ×" over the light sleeve at the top right.
        if (onClose != null) {
            TextKey(
                "close ×", onClose, "setup-close",
                Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp)
                    .semantics { contentDescription = "Close" },
            )
        }
        if (info) SoulseekIdHelp { info = false }
    }
}

/**
 * A sign-in field of the board: a 10/400 grey label, 8 dp, the 16/400 value (placeholder in
 * #B2B2B2), 8 dp, then a 1 dp rule, ink, or #CCCCCC on the field you're not in while typing.
 * Tagged `field:<name>` like every TE field.
 */
@Composable
private fun LineField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
    name: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    quietRule: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    onAction: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val rule = if (quietRule && !focused) Room.Hairline else Room.Ink
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind { drawLine(rule, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx()) }
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(label, style = Room.LabelQuiet)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                autoCorrectEnabled = false,
                imeAction = imeAction,
            ),
            keyboardActions = if (onAction != null) KeyboardActions(onDone = { onAction() }) else KeyboardActions.Default,
            textStyle = Room.Heading,
            cursorBrush = SolidColor(Room.Signal),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("field:$name")
                .semantics { contentDescription = name }
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) BasicText(placeholder, style = Room.Heading.copy(color = Room.Faint))
                    inner()
                }
            },
        )
    }
}

/** What "what's this?" says, in the TE help panel over the grey scrim: Soulseek has no sign-up page. */
@Composable
private fun SoulseekIdHelp(onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        TeScrim(onDismiss, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Room.Background).windowInsetsPadding(WindowInsets.navigationBars)) {
            TeHelpPanel(
                "how a soulseek id works",
                listOf(
                    "soulseek has no sign-up page. your id is created the first time you sign in with it.",
                    "new here? type the id you'd like and a password, then connect. if soulseek says the password is wrong, that id already belongs to someone: try another one.",
                    "keep your password somewhere safe: soulseek can't reset it. signing in here signs out any other app using the same id.",
                ),
                onDismiss,
                tag = "soulseek-id-help",
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Steps

@Composable
private fun OfflineStep(state: RoomState, actions: RoomActions, onDone: () -> Unit, onBack: () -> Unit) {
    StepPage(2, onBack, picture = "step-02", copy = {
        StepCopy("offline · no account needed", "BRING YOUR OWN RECORDS", "choose a folder of flac albums. you can connect soulseek later from settings › soulseek.")
        if (state.hasFolder) BasicText("music folder selected. your records will appear in the crate.", style = Room.Label.copy(color = Room.Body))
    }) {
        StepKey(if (state.hasFolder) "change music folder" else "choose music folder", actions::pickFolder, "setup-local-folder", Modifier.fillMaxWidth())
        TextKey(if (state.hasFolder) "start listening" else "skip for now", onDone, "setup-finish")
    }
}

@Composable
private fun DownloadsStep(state: RoomState, actions: RoomActions, onDone: () -> Unit, onBack: () -> Unit) {
    // Choosing the folder is the answer: as soon as a new one comes back, move on.
    val atEntry = remember { state.downloadFolder }
    LaunchedEffect(state.downloadFolder) {
        if (state.downloadFolder != null && state.downloadFolder != atEntry) onDone()
    }
    StepPage(3, onBack, picture = "step-03", copy = {
        StepCopy("keep your records", "WHERE SHOULD DOWNLOADS GO?", "finished flac downloads land in this folder, play in other apps too, and stay if you uninstall sonic.")
    }) {
        StepKey(if (state.downloadFolder == null) "choose download folder" else "change download folder", actions::pickDownloadFolder, "setup-download-folder", Modifier.fillMaxWidth())
        TextKey(if (state.downloadFolder != null) "keep “${state.downloadFolder}”" else "choose later", onDone, "setup-later")
    }
}

@Composable
private fun ShareStep(state: RoomState, actions: RoomActions, onDone: () -> Unit, onBack: () -> Unit) {
    val atEntry = remember { state.sharedFolder }
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(state.sharedFolder, asked) {
        if (asked && state.sharedFolder != null && state.sharedFolder != atEntry) {
            actions.setSoulseekSharing(true)
            onDone()
        }
    }
    StepPage(4, onBack, picture = "step-04", copy = {
        StepCopy(
            "give something back", "SHARE A FOLDER?",
            "the folder you choose becomes public: anyone on soulseek can browse it and download its flac files. nothing else on your phone is shared.",
        )
        PublicNote()
    }) {
        StepKey("share a folder", { asked = true; actions.pickSharedFolder() }, "setup-share", Modifier.fillMaxWidth(), icon = { drawCaution() })
        TextKey("skip", { actions.setSoulseekSharing(false); onDone() }, "setup-share-skip")
    }
}

/**
 * The share step's warning, between the body and the key: a 1 dp amber outline, 11 in, an 8 dp
 * amber lamp beside two 10/400 ink lines. Read as one sentence by TalkBack.
 */
@Composable
private fun PublicNote() {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Room.Warning)
            .padding(12.dp)
            .semantics(mergeDescendants = true) { }
            .testTag("share-public-note"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Level with the first line's capitals.
        Box(Modifier.padding(top = 4.dp).size(8.dp).background(Room.Warning))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText("this folder becomes public", style = Room.Label)
            BasicText("nothing else on your phone is", style = Room.Label)
        }
    }
}

/** What Friday Drops are, once, before the rhythm: nothing to choose, so one key moves on. */
@Composable
private fun DropsStep(onDone: () -> Unit, onBack: () -> Unit) {
    StepPage(5, onBack, picture = "step-05", copy = {
        StepCopy(
            "every friday", "FRIDAY DROPS",
            "ten creative commons albums from the internet archive, the same ten for everyone. listen to any of them, keep three a week.",
        )
    }) {
        StepKey("next", onDone, "setup-drops-next", Modifier.fillMaxWidth())
    }
}

@Composable
private fun RhythmStep(state: RoomState, actions: RoomActions, onDone: () -> Unit, onBack: () -> Unit) {
    val connected = state.soulseekSession.connected
    StepPage(6, onBack, copy = {
        StepCopy("choose your rhythm", "WHEN SHOULD YOU STAY ONLINE?", "both reconnect after a network blip. your own records always play offline.")
        Column(Modifier.fillMaxWidth()) {
            RhythmCard(
                "while using sonic", "less battery and data. messages arrive when you come back.",
                selected = !state.soulseekBackgroundConsent, enabled = connected, tag = "rhythm:app",
            ) { actions.setSoulseekStayOnline(false); onDone() }
            RhythmCard(
                "stay online", "messages and sharing keep going with the screen off, with a disconnect notification.",
                selected = state.soulseekBackgroundConsent, enabled = connected, tag = "rhythm:background",
            ) { actions.setSoulseekStayOnline(true); onDone() }
        }
        BasicText("tap a card to choose · you can change it in settings", style = Room.LabelQuiet)
        if (!connected) ConnectionNotice(state, actions) { }
    })
}

/**
 * A choice you tap as a whole: 16 in, a 16 dp radio square (an 8 dp ink mark when chosen), the
 * 12/400 title over a 10/400 grey line. The chosen card has a 2 dp ink outline, the other #CCCCCC.
 */
@Composable
private fun RhythmCard(title: String, detail: String, selected: Boolean, enabled: Boolean, tag: String, onPick: () -> Unit) {
    val tick = rememberTick()
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .border(if (selected) 2.dp else 1.dp, if (selected) Room.Ink else Room.Hairline)
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { tick(); onPick() }
            .semantics { this.selected = selected; if (selected) contentDescription = "$title, selected" }
            .testTag(tag)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(16.dp).border(1.dp, Room.Ink).padding(4.dp)) {
            if (selected) Box(Modifier.size(8.dp).background(Room.Ink))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(title, style = Room.Text)
            BasicText(detail, style = Room.LabelQuiet.copy(lineHeight = 15.sp), modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * The last screen (TE · Onboarding · Welcome): the RG-1 above an orange square and the greeting
 * in 44/700, "WELCOME IN, <Soulseek ID>." or "WELCOME IN, LISTENER." offline. The greeting pops
 * in, holds for about two seconds (a tap skips it), and the room takes over from the deck.
 *
 * Over the room the RG-1 is the room's own live deck, seen through a window in the page at
 * exactly its place (beside it in landscape, the greeting in the room's column); on its own the
 * page draws the render instead.
 */
@Composable
private fun WelcomeStep(name: String?, deckSkin: DeckSkin, onFinish: () -> Unit) {
    val pop = remember { Animatable(0f) }
    val hold = remember { Animatable(0f) }
    val onFinished by rememberUpdatedState(onFinish)
    val spot = LocalDeckSpot.current
    // A tap skips the hold; either way the room is opened once, after the greeting has stepped off
    // the deck, so it never crosses the room's own words coming in under it.
    var done by remember { mutableStateOf(false) }
    val finish = { done = true }
    val leave = remember { Animatable(1f) }
    LaunchedEffect(done) {
        if (!done) return@LaunchedEffect
        leave.animateTo(0f, tween(GREETING_LEAVE_MS))
        onFinished()
    }
    // The window onto the room's deck opens as the step fades in, and closes again once the page
    // is gone (it fades out whole, window and all, as the room comes in).
    if (spot != null) {
        LaunchedEffect(spot) { androidx.compose.animation.core.animate(spot.window, 1f, animationSpec = tween(WINDOW_OPEN_MS)) { v, _ -> spot.window = v } }
        DisposableEffect(spot) { onDispose { spot.window = 0f } }
    }
    LaunchedEffect(Unit) {
        // The pop settles in about half a second; the hold makes it about two on screen.
        pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f))
        // Held on screen by an animation rather than a delay, so it runs on the frame clock.
        hold.animateTo(1f, tween(WELCOME_HOLD_MS))
        finish()
    }
    val deck = spot?.rect
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Skip") { finish() }
            .testTag("setup-welcome"),
    ) {
        // Landscape puts the deck on the left band: the greeting goes in the room's column beside it.
        val beside = deck != null && maxWidth > maxHeight
        if (deck != null) {
            // Where the window is, for the tests (it draws nothing).
            with(density) {
                Box(Modifier.offset(deck.left.toDp(), deck.top.toDp()).size(deck.width.toDp(), deck.height.toDp()).testTag("welcome-deck"))
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(if (beside) WindowInsetsSides.Vertical + WindowInsetsSides.End else WindowInsetsSides.Vertical + WindowInsetsSides.Horizontal))
                .padding(
                    start = 24.dp + if (beside) with(density) { deck!!.right.toDp() } else 0.dp,
                    end = 24.dp,
                    top = 32.dp,
                    bottom = 64.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.Bottom),
        ) {
            if (deck == null) {
                val captured = introDeckPictures(deckSkin)?.let { rememberOnboardingPicture(it[0], 600.dp) }
                Image(
                    captured?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it) } ?: painterResource(R.drawable.onboarding_deck_empty),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(212.dp),
                )
            }
            Column(
                Modifier.graphicsLayer {
                    val s = 0.82f + 0.18f * pop.value
                    scaleX = s; scaleY = s
                    transformOrigin = TransformOrigin(0f, 1f)
                    alpha = pop.value.coerceIn(0f, 1f) * leave.value
                    translationY = (1f - leave.value) * 12.dp.toPx()
                },
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Box(Modifier.size(24.dp).background(Room.Signal))
                BasicText(
                    "WELCOME IN, ${(name ?: "listener").uppercase()}.",
                    style = Room.Display.copy(lineHeight = 46.sp),
                    modifier = Modifier.fillMaxWidth().semantics { heading() },
                )
                BasicText("make yourself at home", style = Room.Text.copy(color = Room.Body))
            }
        }
    }
}

/** How long the greeting stays after it pops in, before the room comes in. */
private const val WELCOME_HOLD_MS = 1450
/** How long the greeting takes to fade off the deck once it's done, before the room comes in. */
private const val GREETING_LEAVE_MS = 180
/** How long the window onto the room's deck takes to open, with the step's own fade in. */
private const val WINDOW_OPEN_MS = 360
