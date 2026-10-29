package io.github.agitangst.ampulse.ui

import io.github.agitangst.ampulse.domain.ProfileKind
import io.github.agitangst.ampulse.domain.Site
import io.github.agitangst.ampulse.domain.SiteRest
import io.github.agitangst.ampulse.domain.recommended
import io.github.agitangst.ampulse.domain.rotation
import io.github.agitangst.ampulse.domain.sitesFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.math.hypot

class InjectionModelTest {
    private val today = LocalDate.of(2026, 9, 26)

    @Test
    fun specScenarioRecommendsThighAndRestsRightAbdomen() {
        // Справа живота кололи вчера, слева — 4 дня назад, в бёдра — никогда (спецификация «Site rotation»).
        val sites = sitesFor(ProfileKind.Person, intramuscular = false)
        val injections = listOf(
            Site.AbdomenRight to today.minusDays(1).atTime(22, 0),
            Site.AbdomenLeft to today.minusDays(4).atTime(22, 0),
        )
        val rests = rotation(sites, injections, today)
        val best = recommended(rests)
        assertTrue(best == Site.ThighLeft || best == Site.ThighRight)
        assertEquals(SiteTag.Resting, siteTag(rests.first { it.site == Site.AbdomenRight }, best))
        assertEquals(SiteTag.Ok, siteTag(rests.first { it.site == Site.AbdomenLeft }, best))
        assertEquals(SiteTag.Recommended, siteTag(rests.first { it.site == best }, best))
    }

    @Test
    fun recommendedStaysRecommendedEvenIfAllSitesRest() {
        val rest = SiteRest(Site.ThighLeft, 2)
        assertEquals(SiteTag.Recommended, siteTag(rest, Site.ThighLeft))
        assertEquals(SiteTag.Resting, siteTag(rest, Site.AbdomenLeft))
        assertEquals(SiteTag.Ok, siteTag(SiteRest(Site.ThighLeft, 4), null))
        assertEquals(SiteTag.Ok, siteTag(SiteRest(Site.ThighLeft, null), null))
    }

    @Test
    fun injectionKindFollowsLastSiteThenHintThenSubcutaneous() {
        assertFalse(defaultIntramuscular("Инсулин гларгин", null))
        assertTrue(defaultIntramuscular("", Site.ButtockLeft))
        assertTrue(defaultIntramuscular("", Site.OuterThighRight))
        // Место прошлого укола важнее подсказки в заметке.
        assertFalse(defaultIntramuscular("Дипроспан в/м", Site.AbdomenLeft))
        assertTrue(defaultIntramuscular("Дипроспан в/м", null))
        assertTrue(defaultIntramuscular("Вводить внутримышечно", null))
        assertTrue(defaultIntramuscular("Ceftriaxone IM", null))
        assertTrue(defaultIntramuscular("intramuscular injection", null))
        assertFalse(defaultIntramuscular("Гепарин п/к", null))
        assertFalse(defaultIntramuscular("Введение в/м или п/к", null))
        // «im» внутри слова — не подсказка.
        assertFalse(defaultIntramuscular("Imodium", null))
    }

    @Test
    fun mirrorSwapsSidesAndKeepsScruff() {
        Site.entries.forEach { assertEquals(it, mirrorSite(mirrorSite(it))) }
        assertEquals(Site.FlankRight, mirrorSite(Site.FlankLeft))
        assertEquals(Site.ThighLeft, mirrorSite(Site.ThighRight))
        assertEquals(Site.Scruff, mirrorSite(Site.Scruff))
        Site.entries.filter { it != Site.Scruff }.forEach { assertTrue(it != mirrorSite(it)) }
    }

    @Test
    fun petSideShowsScruffOnBothSides() {
        val sites = sitesFor(ProfileKind.Cat, intramuscular = false)
        assertEquals(listOf(Site.Scruff, Site.FlankLeft), visibleSites(sites, PetSide.Left))
        assertEquals(listOf(Site.Scruff, Site.FlankRight), visibleSites(sites, PetSide.Right))
        // У человека видны все места.
        assertEquals(sitesFor(ProfileKind.Person, false), visibleSites(sitesFor(ProfileKind.Person, false), null))
        assertNull(Site.Scruff.petSide())
        assertEquals(PetSide.Left, Site.PetThighLeft.petSide())
    }

    @Test
    fun everySiteHasASpotInsideTheGridAndSpotsAreFarEnoughApart() {
        for (kind in ProfileKind.entries) {
            for (intramuscular in listOf(false, true)) {
                val view = bodyViewFor(kind, intramuscular)
                val (w, h) = bodyGrid(view)
                val pet = kind != ProfileKind.Person
                val sides: List<PetSide?> = if (pet) PetSide.entries else listOf(null)
                for (side in sides) {
                    val shown = visibleSites(sitesFor(kind, intramuscular), side)
                    assertTrue(shown.isNotEmpty())
                    val spots = shown.map { site ->
                        val (x, y) = checkNotNull(spotOf(view, site)) { "no spot for $site in $view" }
                        // Левый бок питомца зеркалит рисунок.
                        (if (side == PetSide.Left) w - x else x) to y
                    }
                    // Точка — круг 44 в зоне 48: целиком на сетке рисунка.
                    spots.forEach { (x, y) ->
                        assertTrue(x - 24 >= 0 && x + 24 <= w, "x=$x outside $w in $view")
                        assertTrue(y - 24 >= 0 && y + 24 <= h, "y=$y outside $h in $view")
                    }
                    // Зоны нажатия 48 не пересекаются.
                    for (i in spots.indices) for (j in i + 1 until spots.size) {
                        val d = hypot(spots[i].first - spots[j].first, spots[i].second - spots[j].second)
                        assertTrue(d >= 48f, "spots ${shown[i]} and ${shown[j]} are $d apart in $view")
                    }
                }
            }
        }
    }

    @Test
    fun bodyViewByKindAndRoute() {
        assertEquals(BodyView.Front, bodyViewFor(ProfileKind.Person, false))
        assertEquals(BodyView.Back, bodyViewFor(ProfileKind.Person, true))
        assertEquals(BodyView.Cat, bodyViewFor(ProfileKind.Cat, false))
        assertEquals(BodyView.Dog, bodyViewFor(ProfileKind.Dog, true))
        assertEquals(BodyView.Cat, bodyViewFor(ProfileKind.Other, false))
    }

    @Test
    fun doseStepByUnitAndAmount() {
        assertEquals(1.0, doseStep("ЕД", 12.0), 1e-9)
        assertEquals(0.1, doseStep("мл", 2.0), 1e-9)
        assertEquals(0.1, doseStep("ml", 0.5), 1e-9)
        assertEquals(0.5, doseStep("ЕД", 2.5), 1e-9)
        assertEquals(10.0, doseStep("мг", 120.0), 1e-9)
    }

    @Test
    fun doseStepsWithoutFloatDriftAndStaysInRange() {
        assertEquals(13.0, stepDose(12.0, 1.0, 1), 1e-9)
        assertEquals(0.4, stepDose(0.3, 0.1, 1), 1e-9)
        assertEquals(0.3, stepDose(0.4, 0.1, -1), 1e-9)
        assertEquals(2.5, stepDose(2.0, 0.5, 1), 1e-9)
        // Не ниже шага и не выше 1000.
        assertEquals(1.0, stepDose(1.0, 1.0, -1), 1e-9)
        assertEquals(0.1, stepDose(0.1, 0.1, -1), 1e-9)
        assertEquals(1000.0, stepDose(1000.0, 10.0, 1), 1e-9)
        var v = 0.1
        repeat(30) { v = stepDose(v, 0.1, 1) }
        assertEquals(3.1, v, 1e-9)
    }

    @Test
    fun glucoseInputKeepsDigitsAndOneSeparator() {
        assertEquals("6.4", glucoseInput("6.4"))
        assertEquals("6,4", glucoseInput("6,4"))
        assertEquals("64", glucoseInput("6a4"))
        assertEquals("6.45", glucoseInput("6.4.5"))
        assertEquals("5", glucoseInput(".5"))
        assertEquals("12.34", glucoseInput("12.3456"))
    }

    @Test
    fun glucoseValueAndNoteNumber() {
        assertEquals(6.4, glucoseValue("6,4")!!, 1e-9)
        assertEquals("6.4", glucoseNumber("6,4"))
        assertEquals("6", glucoseNumber("6"))
        assertEquals("6.4", glucoseNumber("6.40"))
        assertEquals("6", glucoseNumber("6."))
        assertNull(glucoseNumber(""))
        assertNull(glucoseNumber("0"))
        assertNull(glucoseNumber("0.05"))
        assertNull(glucoseNumber("99"))
        assertNull(glucoseNumber("abc"))
    }

    @Test
    fun spotNumberShowsDashAndCapsLargeNumbers() {
        assertEquals("–", spotNumber(null))
        assertEquals("0", spotNumber(0))
        assertEquals("6", spotNumber(6))
        assertEquals("99", spotNumber(99))
        assertEquals("99+", spotNumber(100))
    }
}
