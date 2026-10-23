package com.hubra.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HubraUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun pressBack() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);compose.waitForIdle() }
    @Test fun fourPagesAreUsableAndColdStartDoesNotFocusInput() {
        compose.onNodeWithTag("chat.input").assertExists().assertIsNotFocused()
        compose.onNodeWithTag("nav.ledger").performClick()
        compose.onNodeWithText("记一笔").performClick()
        compose.onNodeWithText("金额（元）",useUnmergedTree=true).assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("nav.timetable").performClick()
        compose.onNodeWithTag("nav.tasks").performClick()
        compose.onNodeWithTag("nav.chat").performClick()
        compose.onNodeWithTag("chat.input").assertExists()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("使用与显示").assertExists()
    }
    @Test fun systemBackFromNestedSettingsReturnsToPreviousTabThenChat() {
        compose.onNodeWithTag("nav.ledger").performClick()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNode(hasText("备份与导出") and hasClickAction()).performClick()
        compose.onNodeWithText("完整恢复").assertExists()
        pressBack()
        compose.onNodeWithText("使用与显示").assertExists()
        pressBack()
        compose.onNodeWithTag("nav.ledger").assertIsSelected()
        pressBack()
        compose.onNodeWithTag("nav.chat").assertIsSelected()
    }

    @Test fun tasksExposeExpiredTabAndAiContextSetting() {
        compose.onNodeWithTag("nav.tasks").performClick()
        compose.onNodeWithText("已过期").assertExists()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("AI 与记账规则").performClick()
        compose.onNodeWithText("设置 AI 上下文条数").assertExists()
    }
}
