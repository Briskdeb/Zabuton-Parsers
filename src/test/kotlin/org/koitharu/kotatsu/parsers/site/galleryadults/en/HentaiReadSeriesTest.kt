package org.koitharu.kotatsu.parsers.site.galleryadults.en

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HentaiReadSeriesTest {

	@Test
	fun `extracts plain numbered title`() {
		assertEquals(
			NumberedSeriesTitle(base = "my sister", number = 2),
			"My Sister 2".toNumberedSeriesTitle(),
		)
	}

	@Test
	fun `extracts labelled numbered titles`() {
		assertEquals(
			NumberedSeriesTitle(base = "my sister", number = 3),
			"My Sister - Part 3".toNumberedSeriesTitle(),
		)
		assertEquals(
			NumberedSeriesTitle(base = "my sister", number = 4),
			"My Sister: Ch. 4".toNumberedSeriesTitle(),
		)
	}

	@Test
	fun `normalizes punctuation in base title`() {
		assertEquals(
			"My-Sister Story! 1".toNumberedSeriesTitle()?.base,
			"My Sister Story - 2".toNumberedSeriesTitle()?.base,
		)
	}

	@Test
	fun `rejects likely years and unnumbered titles`() {
		assertNull("Summer Vacation 2026".toNumberedSeriesTitle())
		assertNull("My Sister".toNumberedSeriesTitle())
	}
}
