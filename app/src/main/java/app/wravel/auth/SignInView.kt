//
//  SignInView.kt
//  wravel
//

package app.wravel.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wravel.AppAnimation
import app.wravel.AppFont
import app.wravel.AppTextField
import app.wravel.AppTheme
import app.wravel.LocalTint
import app.wravel.PlainTextField
import app.wravel.ProminentFormButton
import app.wravel.WravelMark
import app.wravel.appError
import app.wravel.appField
import app.wravel.appFieldCornerRadius
import app.wravel.appFieldTextColor
import app.wravel.medium
import app.wravel.networking.localizedDescription
import app.wravel.plainClickable
import app.wravel.white
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
            .background(Color.white(0.05f))
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
            PasswordField(
                text = password,
                onTextChange = { password = it },
                modifier = Modifier.padding(top = fieldSpacing),
                onGo = ::submit,
            )
        }

        // Keeps the last message while it fades out, so the box doesn't empty first.
        var shownError by remember { mutableStateOf("") }
        errorMessage?.let { shownError = it }
        AnimatedVisibility(
            visible = errorMessage != null,
            enter = fadeIn(AppAnimation.steepEase(errorDuration)) +
                scaleIn(AppAnimation.steepEase(errorDuration), initialScale = 0.95f, transformOrigin = TransformOrigin(0.5f, 0f)),
            exit = fadeOut(AppAnimation.steepEase(errorDuration)) +
                scaleOut(AppAnimation.steepEase(errorDuration), targetScale = 0.95f, transformOrigin = TransformOrigin(0.5f, 0f)),
        ) {
            Text(
                shownError,
                style = AppFont.footnote,
                color = Color.appError,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.appError.copy(alpha = 0.15f), RoundedCornerShape(appFieldCornerRadius))
                    .padding(14.dp)
                    .animateContentSize(AppAnimation.steepEase(errorDuration)),
            )
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
        Column(
            modifier = Modifier.padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WravelMark(Modifier.width(72.dp))
            Text("wravel", style = AppFont.subheadline.medium.copy(letterSpacing = 1.sp))
        }
        Text(
            if (mode == Mode.SignIn) "Where are we going next?" else "Create your account",
            color = LocalContentColor.current.copy(alpha = 0.6f),
        )
    }
}

/**
 * The password input: a field with an eye on its trailing edge that reveals or hides
 * the text. Switching keeps the keyboard up, since it's the same field throughout.
 */
@Composable
private fun PasswordField(
    text: String,
    onTextChange: (String) -> Unit,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isRevealed by rememberSaveable { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.CenterEnd) {
        PlainTextField(
            text = text,
            onTextChange = onTextChange,
            placeholder = "Password",
            // Keep the text clear of the eye on both sides so it stays centered.
            modifier = Modifier.fillMaxWidth().appField().padding(horizontal = 30.dp),
            textStyle = fieldStyle,
            color = appFieldTextColor,
            placeholderColor = Color.Black.copy(alpha = 0.3f),
            visualTransformation = if (isRevealed) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = if (isRevealed) KeyboardType.Text else KeyboardType.Password,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
        )
        Box(
            modifier = Modifier
                .padding(end = 2.dp)
                .size(44.dp)
                .plainClickable { isRevealed = !isRevealed },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isRevealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = if (isRevealed) "Hide password" else "Show password",
                tint = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private val fieldStyle = AppFont.body.copy(textAlign = TextAlign.Center)

/** The gap between one field and the next. */
private val fieldSpacing = 12.dp

/** How long the error box takes to appear or go. */
private const val errorDuration = 0.4

@Preview
@Composable
private fun SignInViewPreview() {
    AppTheme { SignInView() }
}
