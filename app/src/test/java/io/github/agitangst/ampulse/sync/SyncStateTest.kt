package io.github.agitangst.ampulse.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Различие состояний: что выложить, что удалить, что не трогать; файл состояния. Данные выдуманные. */
class SyncStateTest {
    private val window = MirrorWindow(1_000_000, 2_000_000)

    private val profile = MirrorProfile("prof-aaaaaaaa", "Me", "person", "", "Europe/Berlin")
    private val med = MirrorMed("med-aaaaaaaaaa", profile.id, "Метформин", "pill", "850 мг", "", false)
    private fun intake(n: Int, at: Long, status: String = "pending") =
        MirrorIntake("int-%08d".format(n), med.id, at, status, if (status == "taken") at + 60 else null, "", true)

    private fun mirrorOf(vararg intakes: MirrorIntake, meds: List<MirrorMed> = listOf(med), profiles: List<MirrorProfile> = listOf(profile)) =
        MirrorSet(profiles.associateBy { it.id }, meds.associateBy { it.id }, intakes.associateBy { it.id })

    /** Состояние после удачной выгрузки [set]. */
    private fun stateOf(set: MirrorSet) = SyncState(
        profiles = set.profiles.mapValues { Stamp(it.value.hash) },
        meds = set.meds.mapValues { Stamp(it.value.hash, 0, it.value.profileId) },
        intakes = set.intakes.mapValues { Stamp(it.value.hash, it.value.plannedAt, it.value.medId) },
    )

    @Test
    fun emptyStateSendsEverything() {
        val set = mirrorOf(intake(1, 1_100_000), intake(2, 1_200_000))
        val d = SyncDiff.between(set, SyncState(), window)
        assertEquals(1, d.profiles.size)
        assertEquals(1, d.meds.size)
        assertEquals(2, d.intakes.size)
        assertTrue(d.deleteIntakes.isEmpty() && d.deleteMeds.isEmpty() && d.deleteProfiles.isEmpty())
    }

    @Test
    fun repeatSendsNothing() {
        val set = mirrorOf(intake(1, 1_100_000), intake(2, 1_200_000, "taken"))
        assertTrue(SyncDiff.between(set, stateOf(set), window).isEmpty)
    }

    @Test
    fun onlyChangedRecordsAreSent() {
        val before = mirrorOf(intake(1, 1_100_000), intake(2, 1_200_000))
        val after = mirrorOf(intake(1, 1_100_000), intake(2, 1_200_000, "taken"))
        val d = SyncDiff.between(after, stateOf(before), window)
        assertEquals(listOf("int-00000002"), d.intakes.map { it.id })
        assertTrue(d.profiles.isEmpty() && d.meds.isEmpty() && d.deleteIntakes.isEmpty())
        // Правка лекарства — только оно.
        val edited = after.copy(meds = mapOf(med.id to med.copy(note = "новая заметка")))
        val d2 = SyncDiff.between(edited, stateOf(after), window)
        assertEquals(listOf(med.id), d2.meds.map { it.id })
        assertTrue(d2.intakes.isEmpty())
    }

    @Test
    fun vanishedRecordsAreDeletedInsideTheWindowOnly() {
        val before = mirrorOf(intake(1, 1_100_000), intake(2, 1_200_000), intake(3, 500_000, "taken"))
        val after = mirrorOf(intake(1, 1_100_000))
        val d = SyncDiff.between(after, stateOf(before), window)
        // Приём 2 пропал внутри окна (отменили отметку, схема изменилась) — удаляется; приём 3 просто вышел из окна
        // со временем — остаётся на сервере.
        assertEquals(listOf("int-00000002"), d.deleteIntakes)
    }

    @Test
    fun deletedMedicineAndProfileAreDeleted() {
        val before = mirrorOf(intake(1, 1_100_000))
        val d = SyncDiff.between(mirrorOf(meds = emptyList(), profiles = listOf(profile)), stateOf(before), window)
        assertEquals(listOf(med.id), d.deleteMeds)
        assertEquals(listOf("int-00000001"), d.deleteIntakes)
        assertTrue(d.deleteProfiles.isEmpty())
        val gone = SyncDiff.between(MirrorSet.EMPTY, stateOf(before), window)
        assertEquals(listOf(profile.id), gone.deleteProfiles)
    }

    @Test
    fun refusedContentIsNotResentUntilItChanges() {
        val bad = profile.copy(name = "")
        val set = mirrorOf(intake(1, 1_100_000), profiles = listOf(bad))
        val state = SyncState(rejected = mapOf("profile/${bad.id}" to "${bad.hash}:bad_name"))
        val d = SyncDiff.between(set, state, window)
        assertTrue(d.profiles.isEmpty())
        // Лекарство и приём отклонённого профиля сервер не примет — их тоже не шлём.
        assertTrue(d.meds.isEmpty() && d.intakes.isEmpty())
        assertEquals(listOf("bad_name"), state.refusedCodes)
        // Имя исправили — запись уходит снова.
        val fixed = mirrorOf(intake(1, 1_100_000), profiles = listOf(bad.copy(name = "Я")))
        assertEquals(1, SyncDiff.between(fixed, state, window).profiles.size)
    }

    @Test
    fun stateFileRoundTripsAndSurvivesGarbage() {
        val set = mirrorOf(intake(1, 1_100_000), intake(2, 1_200_000, "taken"))
        val state = stateOf(set).copy(rejected = mapOf("med/x" to "h:bad_name"))
        assertEquals(state, SyncState.fromJson(state.toJson()))
        assertEquals(4, state.stored)
        assertEquals(SyncState(), SyncState.fromJson(null))
        assertEquals(SyncState(), SyncState.fromJson(""))
        assertEquals(SyncState(), SyncState.fromJson("{не json"))
        assertEquals(SyncState(), SyncState.fromJson("""{"v":1,"profiles":{"a":"не массив"}}"""))
    }

    @Test
    fun oldIntakesAreForgottenByThePruner() {
        val state = SyncState(
            intakes = mapOf("old" to Stamp("h", 100), "new" to Stamp("h", 5_000), "plain" to Stamp("h", 0)),
        )
        assertEquals(setOf("new", "plain"), state.pruned(1_000).intakes.keys)
    }
}
