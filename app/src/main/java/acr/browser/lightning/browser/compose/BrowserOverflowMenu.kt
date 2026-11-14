package acr.browser.lightning.browser.compose

import acr.browser.lightning.BrowserUiEvent
import acr.browser.lightning.R
import acr.browser.lightning.browser.BrowserComposeState
import acr.browser.lightning.browser.BrowserPresenter
import acr.browser.lightning.browser.menu.MenuSelection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

@Composable
fun BrowserOverflowMenu(presenter: BrowserPresenter, browserViewState: BrowserComposeState) {
    Box {
        var dropDownExpanded by remember { mutableStateOf(false) }
        IconButton(onClick = {
            dropDownExpanded = !dropDownExpanded
        }) {
            Icon(
                painter = painterResource(R.drawable.more),
                contentDescription = "more"
            )
        }
        DropdownMenu(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .defaultMinSize(minWidth = 175.dp),
            shape = MaterialTheme.shapes.small,
            properties = PopupProperties(focusable = true, clippingEnabled = false),
            expanded = dropDownExpanded,
            onDismissRequest = { dropDownExpanded = false }
        ) {
            if (!browserViewState.isIncognito) {
                DropdownMenuItem(
                    text = { DropdownText(stringResource(R.string.action_incognito)) },
                    onClick = {
                        presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.NEW_INCOGNITO_TAB))
                        dropDownExpanded = false
                    }
                )
            }
            if (!browserViewState.isIncognito) {
                DropdownMenuItem(
                    text = { DropdownText(stringResource(R.string.action_history)) },
                    onClick = {
                        presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.HISTORY))
                        dropDownExpanded = false
                    }
                )
            }
            DropdownMenuItem(
                text = { DropdownText(stringResource(R.string.action_translate_to_arabic)) },
                onClick = {
                    presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.TRANSLATE_TO_ARABIC))
                    dropDownExpanded = false
                }
            )
            if (browserViewState.enableFullMenu) {
                DropdownMenuItem(
                    text = { DropdownText(stringResource(R.string.action_copy)) },
                    onClick = {
                        presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.COPY_LINK))
                        dropDownExpanded = false
                    }
                )
                }
            }
            DropdownMenuItem(
                text = { DropdownText(stringResource(R.string.action_bookmarks)) },
                onClick = {
                    presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.BOOKMARKS))
                    dropDownExpanded = false
                }
            )
            if (!browserViewState.isIncognito) {
                DropdownMenuItem(
                    text = { DropdownText(stringResource(R.string.settings)) },
                    onClick = {
                        presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.SETTINGS))
                        dropDownExpanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun DropdownText(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium
)
