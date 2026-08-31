package com.mako.makoscrubber

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mako.makoscrubber.ui.theme.CauseFont
import com.mako.makoscrubber.ui.theme.MakoCoral

/**
 * Lets the user pin the interface language. "System Default" (the current default) plus the 10
 * shipped languages, each shown in its own autonym and sorted with a locale-aware collator.
 * Saving persists the choice and recreates the activity so the new language takes effect.
 */
@Composable
fun LanguagePickerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val currentTag = remember { LocaleHelper.getPersistedTag(context) }
    var selectedTag by remember { mutableStateOf(currentTag) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.language),
                fontFamily = CauseFont,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                LanguageRow(
                    label = stringResource(R.string.system_default),
                    selected = selectedTag.isEmpty(),
                    onSelect = { selectedTag = "" }
                )
                LocaleHelper.languagesSorted.forEach { lang ->
                    LanguageRow(
                        label = lang.autonym,
                        selected = selectedTag == lang.tag,
                        onSelect = { selectedTag = lang.tag }
                    )
                }
                // Breathing room so the last row never sits flush against the scroll clip.
                Spacer(Modifier.height(8.dp))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedTag != currentTag) {
                        LocaleHelper.persistTag(context, selectedTag)
                        (context as? Activity)?.recreate()
                    } else {
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MakoCoral)
            ) {
                Text(stringResource(R.string.save), fontFamily = CauseFont, color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), fontFamily = CauseFont, color = Color.Gray)
            }
        }
    )
}

@Composable
private fun LanguageRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = MakoCoral)
        )
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
