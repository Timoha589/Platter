package com.platter.desktop.ui.screens

import com.platter.desktop.i18n.t
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.platter.desktop.wave.PremiumServer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.platter.desktop.AppController
import com.platter.desktop.addressLabel
import com.platter.desktop.api.SubsonicException
import com.platter.desktop.data.SavedServer
import com.platter.desktop.label
import com.platter.desktop.ui.OutlinedPill
import com.platter.desktop.ui.PlatterColors
import com.platter.desktop.ui.PlatterMark
import com.platter.desktop.ui.PlatterShapes
import com.platter.desktop.ui.PrimaryPill
import com.platter.desktop.ui.hoverFill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/** Which of the sign-in pages is up: the servers to pick from, the full form for another one, or Timoha Premium's short one. */
private enum class LoginPage { Servers, Custom, Premium }

/** The offer's sweep: the one brand-coloured surface the design allows, as on the phone. */
private val PREMIUM_SWEEP = Brush.horizontalGradient(listOf(Color(0xFFAF2896), Color(0xFF509BF5)))

/**
 * Sign-in. With servers kept from earlier it is a list to pick from, as on the phone: a press signs in with the
 * password kept for that server. A new one is added from the form, which is all there is while the list is empty.
 */
@Composable
fun LoginScreen(app: AppController) {
    var server by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    /** The saved server being signed in to, so its row can say so. */
    var working by remember { mutableStateOf<String?>(null) }

    var page by remember { mutableStateOf(LoginPage.Servers) }

    /** Timoha Premium is offered until it is among the servers, from the first start: a name and a password are all it needs. */
    val offerPremium = app.servers.none { PremiumServer.isPremium(it.address) }
    val scope = rememberCoroutineScope()

    /** Runs a sign-in and turns what goes wrong into a line under the fields. */
    fun attempt(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubsonicException) {
                error = if (e.isWrongCredentials) t("Wrong username or password.") else e.message
            } catch (e: IOException) {
                error = t("Could not reach the server. Check the address.")
            } catch (e: Exception) {
                error = e.message ?: t("Could not sign in.")
            } finally {
                busy = false
                working = null
            }
        }
    }

    fun submit() {
        if (busy || user.isBlank() || password.isEmpty()) return
        if (page == LoginPage.Premium) {
            attempt { app.signIn(PremiumServer.ADDRESS, user, password, PremiumServer.NAME) }
        } else {
            if (server.isBlank()) return
            attempt { app.signIn(server, user, password, name) }
        }
    }

    fun pick(saved: SavedServer) {
        if (busy) return
        working = saved.id
        attempt {
            if (!app.signInTo(saved)) {
                // The password cannot be read back (another Windows user, or no DPAPI): ask for it, with the rest filled in.
                server = saved.address.orEmpty()
                name = saved.name.orEmpty()
                user = saved.username.orEmpty()
                password = ""
                page = if (PremiumServer.isPremium(saved.address)) LoginPage.Premium else LoginPage.Custom
                error = t("Type the password for %s to sign in.", saved.label())
            }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = PlatterColors.White,
        unfocusedBorderColor = PlatterColors.Steel,
        focusedLabelColor = PlatterColors.White,
        unfocusedLabelColor = PlatterColors.Mist,
        cursorColor = PlatterColors.White,
    )

    Box(Modifier.fillMaxSize().background(PlatterColors.Black), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(400.dp).clip(PlatterShapes.Card).background(PlatterColors.Carbon).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatterMark(32.dp)
                Spacer(Modifier.width(12.dp))
                Text("Platter", style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when (page) {
                    LoginPage.Servers -> t("Choose a server")
                    LoginPage.Custom -> t("Sign in to your Subsonic server")
                    LoginPage.Premium -> t("Sign in with your music.timoha.top account")
                },
                style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist,
            )
            Spacer(Modifier.height(24.dp))

            if (page == LoginPage.Premium) {
                OutlinedTextField(
                    user, { user = it }, label = { Text(t("Username")) }, singleLine = true,
                    shape = PlatterShapes.Card, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    password, { password = it }, label = { Text(t("Password")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = PlatterShapes.Card, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
            } else if (page == LoginPage.Custom) {
                OutlinedTextField(
                    server, { server = it }, label = { Text(t("Server address")) }, singleLine = true,
                    placeholder = { Text("music.example.com", color = PlatterColors.Fog) },
                    shape = PlatterShapes.Card, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    name, { name = it }, label = { Text(t("Name (optional)")) }, singleLine = true,
                    shape = PlatterShapes.Card, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    user, { user = it }, label = { Text(t("Username")) }, singleLine = true,
                    shape = PlatterShapes.Card, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    password, { password = it }, label = { Text(t("Password")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = PlatterShapes.Card, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
            } else {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (offerPremium) {
                        PremiumOffer(busy) {
                            user = ""
                            password = ""
                            error = null
                            page = LoginPage.Premium
                        }
                    }
                    app.servers.forEach { saved ->
                        ServerRow(saved, working == saved.id, busy, onPick = { pick(saved) }, onForget = { app.forgetServer(saved.id) })
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = PlatterColors.Liked, modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(24.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (page != LoginPage.Servers) {
                    // The forms can be left without signing in.
                    OutlinedPill(t("Back")) { page = LoginPage.Servers; error = null }
                    PrimaryPill(if (busy) t("Signing in…") else t("Sign in"), enabled = !busy, onClick = ::submit)
                } else {
                    PrimaryPill(t("Add a server"), enabled = !busy) {
                        server = ""
                        name = ""
                        user = ""
                        password = ""
                        error = null
                        page = LoginPage.Custom
                    }
                }
            }
        }
    }
}

/** A saved server: its name, who it is signed in as and where; a press signs in, and the cross forgets it. */
@Composable
private fun ServerRow(saved: SavedServer, working: Boolean, busy: Boolean, onPick: () -> Unit, onForget: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(PlatterShapes.Card)
            .hoverFill(rest = PlatterColors.Graphite, hover = PlatterColors.Smoke)
            .clickable(enabled = !busy, onClick = onPick).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Dns, null, tint = PlatterColors.Mist, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(saved.label(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (working) t("Signing in…") else "${saved.username.orEmpty()} · ${addressLabel(saved.address)}",
                style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Outlined.Close, t("Remove server"), tint = PlatterColors.Mist,
            modifier = Modifier.size(32.dp).clip(CircleShape).clickable(enabled = !busy, onClick = onForget).padding(4.dp),
        )
    }
}

/** The offer to sign in to Timoha Premium: the whole card is the press, the pill only says what pressing does. */
@Composable
private fun PremiumOffer(busy: Boolean, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().testTag("premium-offer").clip(PlatterShapes.Card).background(PREMIUM_SWEEP)
            .clickable(enabled = !busy, onClick = onPick).padding(16.dp),
    ) {
        Text(PremiumServer.NAME, style = MaterialTheme.typography.titleMedium, color = PlatterColors.White)
        Text(PremiumServer.HOST, style = MaterialTheme.typography.bodySmall, color = PlatterColors.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 2.dp))
        Text(
            t("A ready-made library with My Wave. Just sign in with your account."),
            style = MaterialTheme.typography.bodyMedium, color = PlatterColors.White, modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(16.dp))
        PrimaryPill(t("Sign in"), enabled = !busy, onClick = onPick)
    }
}
