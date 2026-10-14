package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.auth.AuthUiState
import au.edu.unimelb.campuscompanion.auth.AuthenticatedUser

@Composable
fun AuthLoadingScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun LoginScreen(
    state: AuthUiState,
    onGoogleSignIn: () -> Unit,
    onAppleSignIn: () -> Unit,
    onSendEmailCode: (String) -> Unit,
    onVerifyEmailCode: (String) -> Unit,
    onResendEmailCode: () -> Unit,
    onChangeEmail: () -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val controlsEnabled = state.isConfigured && !state.isSubmitting

    LaunchedEffect(state.pendingEmail) {
        if (state.pendingEmail == null) code = ""
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 34.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp)
                )
                Text(
                    text = "Campus Companion",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Sign in to continue",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!state.isConfigured) {
                MessageSurface(
                    message = state.infoMessage
                        ?: "Authentication is not configured on this device.",
                    isError = false
                )
            }

            state.errorMessage?.let { message ->
                MessageSurface(message = message, isError = true)
            }
            if (state.errorMessage == null && state.isConfigured) {
                state.infoMessage?.let { message ->
                    MessageSurface(message = message, isError = false)
                }
            }

            ProviderButton(
                label = "Continue with Google",
                icon = Icons.Outlined.Language,
                enabled = controlsEnabled,
                onClick = {
                    onClearMessage()
                    onGoogleSignIn()
                }
            )
            ProviderButton(
                label = "Continue with Apple",
                icon = Icons.Outlined.PhoneIphone,
                enabled = controlsEnabled,
                onClick = {
                    onClearMessage()
                    onAppleSignIn()
                }
            )

            OrDivider()

            if (state.pendingEmail == null) {
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        onClearMessage()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSubmitting,
                    singleLine = true,
                    label = { Text("Email address") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Email, contentDescription = null)
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (controlsEnabled) onSendEmailCode(email)
                        }
                    )
                )
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        onSendEmailCode(email)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = controlsEnabled
                ) {
                    ButtonLabel(
                        text = "Send email code",
                        showProgress = state.isSubmitting
                    )
                }
                Text(
                    text = "You can use any valid email address.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = state.pendingEmail,
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it.filter(Char::isDigit).take(6)
                        onClearMessage()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSubmitting,
                    singleLine = true,
                    label = { Text("6-digit code") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (controlsEnabled) onVerifyEmailCode(code)
                        }
                    )
                )
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        onVerifyEmailCode(code)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = controlsEnabled && code.length == 6
                ) {
                    ButtonLabel(
                        text = "Verify and sign in",
                        showProgress = state.isSubmitting
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = onChangeEmail,
                        enabled = !state.isSubmitting
                    ) {
                        Text("Change email")
                    }
                    TextButton(
                        onClick = onResendEmailCode,
                        enabled = controlsEnabled
                    ) {
                        Text("Resend code")
                    }
                }
            }
        }
    }
}

@Composable
fun CompleteProfileScreen(
    user: AuthenticatedUser,
    state: AuthUiState,
    onSaveName: (String) -> Unit,
    onSignOut: () -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var name by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.AccountCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = "Complete your profile",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = user.email ?: "Signed in with ${user.provider.displayName}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Enter the name that should appear in Campus Companion.",
            style = MaterialTheme.typography.bodyLarge
        )

        state.errorMessage?.let { MessageSurface(message = it, isError = true) }

        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it.take(80)
                onClearMessage()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isSubmitting,
            singleLine = true,
            label = { Text("Display name") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    onSaveName(name)
                }
            )
        )
        Button(
            onClick = {
                focusManager.clearFocus()
                onSaveName(name)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = !state.isSubmitting
        ) {
            ButtonLabel(text = "Continue", showProgress = state.isSubmitting)
        }
        OutlinedButton(
            onClick = onSignOut,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isSubmitting
        ) {
            Text("Use a different account")
        }
    }
}

@Composable
private fun ProviderButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        enabled = enabled,
        shape = RoundedCornerShape(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.size(10.dp))
        Text(label)
    }
}

@Composable
private fun ButtonLabel(text: String, showProgress: Boolean) {
    if (showProgress) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary
        )
    } else {
        Text(text)
    }
}

@Composable
private fun OrDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = "OR",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MessageSurface(message: String, isError: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        contentColor = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        }
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
