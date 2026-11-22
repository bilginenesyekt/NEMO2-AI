package com.example

import com.example.launcher.AppLauncherHelper
import com.example.voice.VoiceCommandRouter
import com.example.voice.VoiceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun `uygulamayi ac opens recent actions`() {
    assertEquals(VoiceIntent.ShowRecentActions, VoiceCommandRouter.parse("uygulamayı aç"))
    assertEquals(VoiceIntent.ShowRecentActions, VoiceCommandRouter.parse("Nemoyu aç"))
    assertEquals(VoiceIntent.ShowRecentActions, VoiceCommandRouter.parse("son işlemlerimi göster"))
  }

  @Test
  fun `specific external app commands route to OpenExternalApp`() {
    assertEquals(
      VoiceIntent.OpenExternalApp("youtube"),
      VoiceCommandRouter.parse("YouTube uygulamasını aç")
    )
    assertEquals(
      VoiceIntent.OpenExternalApp("spotify"),
      VoiceCommandRouter.parse("Nemoyu şu uygulamayı aç: Spotify")
    )
    assertEquals(
      VoiceIntent.OpenExternalApp("kamerayi"),
      VoiceCommandRouter.parse("Kamerayı aç")
    )
    assertEquals(
      VoiceIntent.OpenExternalApp("chrome"),
      VoiceCommandRouter.parse("Open Chrome")
    )
    assertTrue(
      VoiceCommandRouter.parse("Telefon ayarlarını aç") is VoiceIntent.OpenExternalApp
    )
  }

  @Test
  fun `internal settings and normal chat prompts route accurately`() {
    assertEquals(VoiceIntent.OpenSettings, VoiceCommandRouter.parse("Ayarları aç"))
    assertEquals(
      VoiceIntent.SendChatPrompt("Bugün hava nasıl?"),
      VoiceCommandRouter.parse("Bugün hava nasıl?")
    )
  }

  @Test
  fun `app launcher normalization preserves app stems`() {
    val candidates = AppLauncherHelper.buildQueryCandidates(
      AppLauncherHelper.normalizeQuery("Spotify'ı aç")
    )
    assertTrue(candidates.any { it.contains("spotify") })
  }
}
