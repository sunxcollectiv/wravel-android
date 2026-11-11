//
//  SignInView.kt
//  outbound
//

package earth.outbound.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import earth.outbound.AppFont
import earth.outbound.AppTextField
import earth.outbound.AppTheme
import earth.outbound.LocalTint
import earth.outbound.ProminentFormButton
import earth.outbound.R
import earth.outbound.networking.localizedDescription
import earth.outbound.plainClickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class Mode {
    SignIn, SignUp
}

@Composable
fun SignInView(modifier: Modifier = Modifier) {
    val auth = LocalAuthStore.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var mode by rememberSaveable { mutableStateOf(Mode.SignIn) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        errorMessage = null
        isWorking = true
        focusManager.clearFocus()
        scope.launch {
            try {
                when (mode) {
                    Mode.SignIn -> auth.signIn(email = email, password = password)
                    Mode.SignUp -> auth.signUp(name = name, email = email, password = password)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                errorMessage = error.localizedDescription
            } finally {
                isWorking = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .padding(top = 60.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header(mode)

        Column {
            // Brings the gap under it, so there's none left behind while it's hidden.
            AnimatedVisibility(visible = mode == Mode.SignUp) {
                AppTextField(
                    text = name,
                    onTextChange = { name = it },
                    placeholder = "Name",
                    modifier = Modifier.padding(bottom = fieldSpacing),
                    textStyle = fieldStyle,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
            }
            AppTextField(
                text = email,
                onTextChange = { email = it },
                placeholder = "Email",
                textStyle = fieldStyle,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
            )
            AppTextField(
                text = password,
                onTextChange = { password = it },
                placeholder = "Password",
                modifier = Modifier.padding(top = fieldSpacing),
                textStyle = fieldStyle,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
            )
        }

        errorMessage?.let {
            Text(it, style = AppFont.footnote, color = errorRed, modifier = Modifier.fillMaxWidth())
        }

        ProminentFormButton(
            title = if (mode == Mode.SignIn) "Sign In" else "Create Account",
            onClick = ::submit,
            enabled = !isWorking,
            isWorking = isWorking,
        )

        Text(
            if (mode == Mode.SignIn) "Don't have an account? Sign up" else "Already have an account? Sign in",
            style = AppFont.footnote,
            color = LocalTint.current,
            modifier = Modifier.plainClickable {
                mode = if (mode == Mode.SignIn) Mode.SignUp else Mode.SignIn
                errorMessage = null
            },
        )
    }
}

@Composable
private fun Header(mode: Mode) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            painter = painterResource(R.drawable.outbound_wordmark),
            contentDescription = "Outbound",
            modifier = Modifier.padding(bottom = 16.dp).width(152.dp),
        )
        Text(
            if (mode == Mode.SignIn) "Where are we going next?" else "Create your account",
            color = LocalContentColor.current.copy(alpha = 0.6f),
        )
    }
}

private val fieldStyle = AppFont.body.copy(textAlign = TextAlign.Center)

/** The gap between one field and the next. */
private val fieldSpacing = 12.dp

/** The red iOS shows an error in (its system red). */
private val errorRed = Color(red = 1.0f, green = 0.23f, blue = 0.19f)

@Preview
@Composable
private fun SignInViewPreview() {
    AppTheme { SignInView() }
}
