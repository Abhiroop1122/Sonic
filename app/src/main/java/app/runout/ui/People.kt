package app.runout.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.runout.account.Account
import app.runout.account.AccountRepository
import app.runout.account.AccountStatus
import app.runout.account.RemoteProfile
import app.runout.model.Album
import app.runout.social.Friend
import app.runout.social.Profile
import app.runout.social.ShelfItem

/** What the people search is showing. */
data class PeopleState(
    val results: List<RemoteProfile> = emptyList(),
    val searching: Boolean = false,
    val searched: String? = null,
    val viewing: RemoteProfile? = null,
    val loadingProfile: Boolean = false,
    val error: String? = null,
)

@Composable
fun FriendsPanel(
    profile: Profile,
    account: Account?,
    status: AccountStatus?,
    people: PeopleState,
    friends: List<Friend>,
    shelf: List<Album>,
    actions: RoomActions,
) {
    LaunchedEffect(account?.pubkey) { if (account != null) actions.refreshFriends() }
    val viewing = people.viewing
    Column(Modifier.fillMaxWidth().testTag("panel:friends")) {
        if (viewing != null) {
            ProfilePage(viewing, isFriend = friends.any { it.profile.id == viewing.pubkey }, shelf = shelf, actions = actions)
            return@Column
        }
        if (account == null) CreateAccount(profile, actions) else YourAccount(account, status, actions)

        if (account != null) {
            SectionTitle("Find people")
            var query by rememberSaveable { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field(query, { query = it }, "Search usernames") }
                Spacer(Modifier.width(8.dp))
                Pill(if (people.searching) "Searching…" else "Search", { actions.searchPeople(query) }, active = true, enabled = query.trim().length >= 2 && !people.searching)
            }
            people.error?.let { BasicText(it, style = Room.LabelQuiet.copy(color = Color(0xFFD03A3A)), modifier = Modifier.padding(top = 4.dp)) }
            if (people.searched != null && !people.searching && people.results.isEmpty() && people.error == null) {
                BasicText("Nobody called “${people.searched}” yet.", style = Room.LabelQuiet, modifier = Modifier.padding(top = 8.dp))
            }
            people.results.forEach { p ->
                PersonRow(
                    name = p.name, handle = p.handle, accent = p.accent,
                    detail = "${p.shelfCount} records",
                    trailing = if (friends.any { it.profile.id == p.pubkey }) "Friend" else null,
                    tag = "result:${p.username}",
                    onClick = { actions.openProfile(p.pubkey) },
                )
            }
        }

        SectionTitle("Friends · ${friends.size}")
        if (friends.isEmpty()) {
            BasicText(
                if (account == null) "Make an account to find friends by username, or swap friend cards in person."
                else "Search for a username above, or swap friend cards: open theirs and they appear here.",
                style = Room.LabelQuiet,
            )
        }
        friends.forEach { f -> FriendRow(f, shelf, actions) }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun CreateAccount(profile: Profile, actions: RoomActions) {
    var restoring by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(if (profile.name == "You") "" else profile.name) }
    var accent by rememberSaveable { mutableStateOf(profile.accent) }
    var key by rememberSaveable { mutableStateOf("") }
    val u = AccountRepository.normalize(username)
    val problem = if (u.isEmpty()) null else AccountRepository.validateUsername(u)

    if (!restoring) {
        SectionTitle("Make your account")
        Field(username, { username = it.take(20) }, "Username")
        BasicText(
            problem ?: if (u.isEmpty()) "3–20 letters, numbers, _ or ." else "You'll be @$u, plus a 4-character code from your key",
            style = Room.LabelQuiet.copy(color = if (problem != null) Color(0xFFD03A3A) else Room.Quiet),
        )
        Field(name, { name = it }, "Display name")
        ColourDots(Profile.ACCENTS, accent, { accent = it }, Modifier.padding(vertical = 8.dp))
        Pill("Create account", { actions.createAccount(u, name, accent) }, active = true, enabled = u.isNotEmpty() && problem == null)
        BasicText(
            "There's no Sonic server. Your account is a key made on this phone, and your profile (username, name, colour and shelf) is published, signed, to free public relays so people can find you. Anyone can see it. Nobody can change it but you.",
            style = Room.LabelQuiet, modifier = Modifier.padding(top = 8.dp),
        )
        BasicText(
            "Already have an account? Restore it",
            style = Room.Label.copy(color = Room.Accent),
            modifier = Modifier.padding(top = 10.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { restoring = true },
        )
    } else {
        SectionTitle("Restore your account")
        Field(key, { key = it.trim() }, "Account key (nsec1…)")
        Field(username, { username = it.take(20) }, "Username (only if your profile is gone)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Pill("Restore", { actions.restoreAccount(key, u.ifBlank { null }) }, active = true, enabled = AccountRepository.parseSecret(key) != null)
            Pill("Back", { restoring = false })
        }
    }
}

@Composable
private fun YourAccount(account: Account, status: AccountStatus?, actions: RoomActions) {
    SectionTitle("You")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).background(Color(account.accent), CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            BasicText(account.name, style = Room.Title.copy(fontSize = 15.sp))
            BasicText(account.handle, style = Room.Label.copy(color = Room.Accent), modifier = Modifier.testTag("my-handle"))
        }
    }
    var name by remember(account.pubkey) { mutableStateOf(account.name) }
    Field(name, { name = it; actions.rename(it) }, "Display name")
    ColourDots(Profile.ACCENTS, account.accent, actions::setAccent, Modifier.padding(vertical = 8.dp))
    ToggleRow("Show my records on my profile", "Friends and anyone who finds you see what's on your shelf.", account.shareShelf, actions::setShareShelf)
    status?.let { BasicText(it.message, style = Room.LabelQuiet.copy(color = if (it.ok) Color(0xFF1E8E5A) else Color(0xFFB0641A))) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Pill("Send my friend card", actions::shareMyCard)
        Pill("Copy account key", actions::copyAccountKey)
    }
    BasicText(
        "Keep your account key somewhere safe. It's the only way to get @${account.username}·${account.code} back on a new phone.",
        style = Room.LabelQuiet, modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun PersonRow(name: String, handle: String?, accent: Int, detail: String, trailing: String?, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 7.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).background(Color(accent), CircleShape), contentAlignment = Alignment.Center) {
            BasicText(name.take(1).uppercase(), style = Room.Label.copy(color = Color.White, fontWeight = FontWeight.SemiBold))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            BasicText(name, style = Room.Title.copy(fontSize = 13.sp), maxLines = 1)
            BasicText(listOfNotNull(handle, detail).joinToString(" · "), style = Room.LabelQuiet, maxLines = 1)
        }
        trailing?.let { BasicText(it, style = Room.LabelQuiet) }
        BasicText("  ›", style = Room.Title)
    }
}

@Composable
private fun ProfilePage(p: RemoteProfile, isFriend: Boolean, shelf: List<Album>, actions: RoomActions) {
    BasicText(
        "‹ Back",
        style = Room.Label.copy(color = Room.Accent),
        modifier = Modifier.padding(bottom = 8.dp).testTag("profile-back")
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.closeProfile() },
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("profile:${p.username}")) {
        Box(Modifier.size(44.dp).background(Color(p.accent), CircleShape), contentAlignment = Alignment.Center) {
            BasicText(p.name.take(1).uppercase(), style = Room.Title.copy(color = Color.White, fontSize = 18.sp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(p.name, style = Room.Title.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
            BasicText(p.handle, style = Room.Label.copy(color = Room.Accent))
            val common = p.shelf.count { item -> shelf.any { item.matches(it) } }
            BasicText("${p.shelfCount} records" + if (common > 0) " · $common you both have" else "", style = Room.LabelQuiet)
        }
        if (isFriend) BasicText("Friend ✓", style = Room.Label) else Pill("Add friend", { actions.addFriend(p) }, active = true)
    }
    SectionTitle("Shelf")
    if (p.shelf.isEmpty()) BasicText("${p.name} keeps their shelf private.", style = Room.LabelQuiet)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        p.shelf.forEach { item -> RemoteSleeve(item, shared = shelf.any { item.matches(it) }, actions = actions) }
    }
    if (p.shelfCount > p.shelf.size) BasicText("…and ${p.shelfCount - p.shelf.size} more", style = Room.LabelQuiet, modifier = Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(12.dp))
}

/** A sleeve on someone else's shelf: its colour and title straight away, the cover when it's found. */
@Composable
private fun RemoteSleeve(item: ShelfItem, shared: Boolean, actions: RoomActions) {
    val cover by produceState<ImageBitmap?>(null, item.title, item.artist) {
        value = actions.coverFor(item)?.let { CoverCache.load(it) }
    }
    val shape = RoundedCornerShape(3.dp)
    Column(Modifier.width(84.dp)) {
        Box(
            Modifier
                .size(84.dp)
                .border(if (shared) 2.dp else 0.dp, if (shared) Room.Accent else Color.Transparent, shape)
                .clip(shape)
                .background(item.tint?.let { Color(it) } ?: Color(0xFFE6E6EA)),
        ) {
            val c = cover
            if (c != null) Image(c, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else BasicText(item.title, style = Room.Label.copy(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(8.dp), maxLines = 4)
        }
        BasicText(item.title, style = Room.Label.copy(fontSize = 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        BasicText(item.artist, style = Room.LabelQuiet.copy(fontSize = 9.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FriendRow(f: Friend, shelf: List<Album>, actions: RoomActions) {
    val inCommon = remember(f, shelf) { f.shelf.count { item -> shelf.any { item.matches(it) } } }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("friend:${f.profile.name}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = f.profile.isAccount) {
                actions.openProfile(f.profile.id)
            },
        ) {
            Box(Modifier.size(12.dp).background(Color(f.profile.accent), CircleShape))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                BasicText(f.profile.name, style = Room.Title.copy(fontSize = 13.sp))
                BasicText(
                    listOfNotNull(
                        f.profile.handle,
                        "${f.shelf.size} records" + (if (inCommon > 0) " · $inCommon you both have" else ""),
                        if (f.cards.isNotEmpty()) "sent you ${f.cards.size}" else null,
                    ).joinToString(" · "),
                    style = Room.LabelQuiet,
                )
            }
            Pill("Remove", { actions.removeFriend(f.profile.id) })
        }
        if (f.shelf.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                f.shelf.take(7).forEach { item -> ShelfThumb(item, shared = shelf.any { item.matches(it) }) }
            }
        }
        f.cards.takeLast(3).forEach { card ->
            BasicText("Sent you  ${card.title} — ${card.artist}", style = Room.Label, maxLines = 1)
        }
    }
}

@Composable
private fun ShelfThumb(item: ShelfItem, shared: Boolean) {
    val bmp = remember(item.thumb) {
        item.thumb?.let { t ->
            runCatching {
                val bytes = Base64.decode(t, Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(2.dp)
    Box(
        Modifier
            .size(38.dp)
            .border(if (shared) 2.dp else 0.dp, if (shared) Room.Accent else Color.Transparent, shape)
            .clip(shape)
            .background(item.tint?.let { Color(it) } ?: Color(0xFFE6E6EA)),
    ) {
        if (bmp != null) Image(bmp, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else BasicText(item.title, style = Room.LabelQuiet.copy(fontSize = 6.sp, color = if (item.tint != null) Color.White else Room.Quiet), modifier = Modifier.padding(4.dp), maxLines = 3)
    }
}
