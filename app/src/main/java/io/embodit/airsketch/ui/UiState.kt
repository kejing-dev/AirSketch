package io.embodit.airsketch.ui

import io.embodit.airsketch.domain.IndexEntry

enum class Screen { LOADING, DRAW, DISPLAY, ERROR }

/** What the toolbar panel shows (design panels[]: it changes content or grows upwards, one panel). */
enum class Bar { TOOLS, PALETTE, MENU, CONFIRM_CLEAR, GALLERY, CONFIRM_DELETE, SETTINGS }

/** One gallery card: an own work (from the index) or a sample. */
data class Card(val id: String, val sample: String?, val nameIndex: Int, val updatedAt: Long, val strokeCount: Int, val colors: List<Int>) {
    companion object {
        fun of(e: IndexEntry) = Card(e.id, null, e.nameIndex, e.updatedAt, e.strokeCount, e.colorsUsed)
    }
}

data class UiState(
    val screen: Screen = Screen.LOADING,
    val bar: Bar = Bar.TOOLS,
    val lang: Lang = Lang.ZH,
    val langSetting: String = "follow",
    val slowSpin: Boolean = true,
    val brush: Int = 0,
    val size: Int = 1,
    val color: Int = 7,
    val mirror: Boolean = false,
    val strokeCount: Int = 0,
    val canRedo: Boolean = false,
    val full: Boolean = false,
    val drawing: Boolean = false,
    val turning: Boolean = false,
    /** Onboarding sentence 0..3 on the bubble, null when the bubble is gone. */
    val onboarding: Int? = null,
    /** One-line message under the toolbar row (onboarding end), else the stick tip. */
    val message: String? = null,
    val workName: String = "",
    val workId: String? = null,
    val sample: String? = null,
    val cards: List<Card> = emptyList(),
    val errorReason: String? = null,
    val loading: Float = 0f,
    /** Design state id (S01–S17, with +S07 / +S09 markers) for logs and QA. */
    val designState: String = "S01",
) {
    val strings: Strings get() = if (lang == Lang.ZH) ZH else EN

    companion object {
        private val ZH = Strings(Lang.ZH)
        private val EN = Strings(Lang.EN)
    }
}
