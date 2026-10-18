package com.platter.desktop

import com.platter.desktop.update.ReleaseNotes
import com.platter.desktop.update.ReleaseNotes.Heading
import com.platter.desktop.update.ReleaseNotes.Item
import com.platter.desktop.update.ReleaseNotes.Line
import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseNotesTest {
    @Test
    fun `a list of changes is read item by item`() {
        val notes = ReleaseNotes.parse("- Добавлен кроссфейд\n* Исправлен поиск\r\n+ Третье\n• Четвёртое\n1. Пятое\n2) Шестое")
        assertEquals(
            listOf(Item("Добавлен кроссфейд"), Item("Исправлен поиск"), Item("Третье"), Item("Четвёртое"), Item("Пятое"), Item("Шестое")),
            notes,
        )
    }

    @Test
    fun `plain notes stay a line, and blank lines and rules go`() {
        assertEquals(
            listOf(Line("Добавлен Кроссфейд, улучшена работа приложения, прочие фиксы")),
            ReleaseNotes.parse("Добавлен Кроссфейд, улучшена работа приложения, прочие фиксы\n\n---\n"),
        )
        assertEquals(emptyList(), ReleaseNotes.parse("  \n"))
    }

    @Test
    fun `headings are kept, except a title the notice already gives`() {
        val notes = ReleaseNotes.parse("## Что изменилось:\n- Одно\n### Телефон\n- Другое\nЧто изменилось:\n- Третье")
        assertEquals(listOf(Item("Одно"), Heading("Телефон"), Item("Другое"), Item("Третье")), notes)
    }

    @Test
    fun `what GitHub writes into its own notes is cleaned away`() {
        val notes = ReleaseNotes.parse(
            """
            ## What's Changed
            * Crossfade on the phone by @Timoha589 in https://github.com/Timoha589/Platter/pull/12
            * **Faster** search, see [the issue](https://github.com/Timoha589/Platter/issues/3) and `Settings`


            **Full Changelog**: https://github.com/Timoha589/Platter/compare/v1.0.1...v1.0.2
            """.trimIndent(),
        )
        assertEquals(listOf(Item("Crossfade on the phone"), Item("Faster search, see the issue and Settings")), notes)
    }

    @Test
    fun `an item wrapped onto an indented line stays one item`() {
        assertEquals(
            listOf(Item("Длинный пункт, который продолжается"), Item("Следующий")),
            ReleaseNotes.parse("- Длинный пункт,\n  который продолжается\n- Следующий"),
        )
    }
}
