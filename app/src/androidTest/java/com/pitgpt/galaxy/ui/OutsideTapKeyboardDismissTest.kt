package com.pitgpt.galaxy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutsideTapKeyboardDismissTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tapOutsideTextFieldClearsFocus() {
        composeRule.setContent {
            var value by remember { mutableStateOf("") }
            val keyboardDismiss = rememberOutsideTapKeyboardDismissState()
            Box(Modifier.fillMaxSize().then(keyboardDismiss.rootModifier())) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.then(keyboardDismiss.textFieldModifier("input"))
                            .testTag("input")
                    )
                    Spacer(Modifier.height(24.dp))
                    Box(
                        Modifier.size(120.dp)
                            .background(Color.LightGray)
                            .testTag("outside")
                    )
                }
            }
        }

        composeRule.onNodeWithTag("input").performTouchInput { click() }
        composeRule.onNodeWithTag("input").assertIsFocused()
        composeRule.onNodeWithTag("outside").performTouchInput { click() }
        composeRule.onNodeWithTag("input").assertIsNotFocused()
    }

    @Test
    fun swipeOutsideDoesNotClearTextFocus() {
        composeRule.setContent {
            var value by remember { mutableStateOf("") }
            val keyboardDismiss = rememberOutsideTapKeyboardDismissState()
            Box(Modifier.fillMaxSize().then(keyboardDismiss.rootModifier())) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.then(keyboardDismiss.textFieldModifier("input"))
                            .testTag("input")
                    )
                    Spacer(Modifier.height(24.dp))
                    Box(
                        Modifier.size(160.dp)
                            .background(Color.LightGray)
                            .testTag("outside")
                    )
                }
            }
        }

        composeRule.onNodeWithTag("input").performTouchInput { click() }
        composeRule.onNodeWithTag("input").assertIsFocused()
        composeRule.onNodeWithTag("outside").performTouchInput {
            down(center)
            moveBy(Offset(0f, -80f), durationMillis = 100)
            up()
        }
        composeRule.onNodeWithTag("input").assertIsFocused()
    }

    @Test
    fun tappingTextFieldKeepsFocusAndOutsideButtonStillWorks() {
        var buttonClicks = 0
        composeRule.setContent {
            var value by remember { mutableStateOf("") }
            val keyboardDismiss = rememberOutsideTapKeyboardDismissState()
            Box(Modifier.fillMaxSize().then(keyboardDismiss.rootModifier())) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.then(keyboardDismiss.textFieldModifier("input"))
                            .testTag("input")
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { buttonClicks += 1 },
                        modifier = Modifier.testTag("action")
                    ) {
                        Text("Action")
                    }
                }
            }
        }

        composeRule.onNodeWithTag("input").performTouchInput { click() }
        composeRule.onNodeWithTag("input").assertIsFocused()
        composeRule.onNodeWithTag("input").performTouchInput { click() }
        composeRule.onNodeWithTag("input").assertIsFocused()
        composeRule.onNodeWithTag("action").performTouchInput { click() }
        composeRule.onNodeWithTag("input").assertIsNotFocused()
        composeRule.runOnIdle { assertEquals(1, buttonClicks) }
    }
}
