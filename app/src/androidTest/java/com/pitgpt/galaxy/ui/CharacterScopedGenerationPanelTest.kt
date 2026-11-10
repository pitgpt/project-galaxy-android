package com.pitgpt.galaxy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runOnIdle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterScopedGenerationPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun switchingCharacterResetsGenerationPanelState() {
        var characterId by mutableStateOf("character-a")

        composeRule.setContent {
            CharacterScopedGenerationPanel(characterId) {
                var prompt by remember { mutableStateOf("") }
                Column {
                    BasicTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.testTag("generation-prompt")
                    )
                }
            }
        }

        composeRule.onNodeWithTag("generation-prompt").performTextInput("private prompt")
        composeRule.onNodeWithTag("generation-prompt").assertTextEquals("private prompt")

        composeRule.runOnIdle { characterId = "character-b" }

        composeRule.onNodeWithTag("generation-prompt").assertTextEquals("")
    }
}
