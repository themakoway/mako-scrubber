package com.mako.makoscrubber

import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mako.makoscrubber.ui.theme.CauseFont
import com.mako.makoscrubber.ui.theme.MakoCoral

@Composable
fun AboutMakoDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Image(
                painter = painterResource(R.drawable.ic_mako),
                contentDescription = null,
                modifier = Modifier.size(64.dp)
            )
        },
        title = {
            Text(
                text = stringResource(R.string.about_mako_title),
                fontFamily = CauseFont,
                fontWeight = FontWeight.Bold,
                color = MakoCoral
            )
        },
        text = {
            Text(
                text = stringResource(R.string.about_mako_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    // AndroidUriHandler.openUri throws if no activity can handle the URL
                    // (no browser installed / disabled).
                    runCatching { uriHandler.openUri("https://www.makoway.app") }
                        .onFailure {
                            Toast.makeText(context, context.getString(R.string.no_browser), Toast.LENGTH_SHORT).show()
                        }
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MakoCoral)
            ) {
                Text(stringResource(R.string.about_mako_visit), fontFamily = CauseFont, color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close), fontFamily = CauseFont, color = Color.Gray)
            }
        }
    )
}
