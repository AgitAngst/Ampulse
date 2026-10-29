package io.github.agitangst.ampulse.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.domain.Routine
import io.github.agitangst.ampulse.domain.RoutineStore
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.SectionTitle
import java.time.LocalTime

/** Что из режима дня правится строкой: подъём, завтрак, обед, ужин, сон. */
private enum class RoutinePart(@StringRes val label: Int) {
    Wake(R.string.routine_wake),
    Breakfast(R.string.routine_breakfast),
    Lunch(R.string.routine_lunch),
    Dinner(R.string.routine_dinner),
    Sleep(R.string.routine_sleep);

    fun get(r: Routine): LocalTime = when (this) {
        Wake -> r.wake
        Breakfast -> r.breakfast
        Lunch -> r.lunch
        Dinner -> r.dinner
        Sleep -> r.sleep
    }

    fun set(r: Routine, t: LocalTime): Routine = when (this) {
        Wake -> r.copy(wake = t)
        Breakfast -> r.copy(breakfast = t)
        Lunch -> r.copy(lunch = t)
        Dinner -> r.copy(dinner = t)
        Sleep -> r.copy(sleep = t)
    }
}

/**
 * «Настройки → Режим дня»: подъём, завтрак, обед, ужин и сон. По ним разбор назначения ставит время приёмов
 * («до еды» — за 30 минут до завтрака, «на ночь» — за час до сна). Изменение сохраняется сразу (docs/ui-guideline.md, §6).
 */
@Composable
fun RoutineSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var routine by remember { mutableStateOf(RoutineStore.load(context)) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.routine_section), Modifier.padding(horizontal = 4.dp))
        AnvilCard {
            Text(stringResource(R.string.routine_hint), color = AnvilTheme.palette.weak, style = MaterialTheme.typography.bodyMedium)
            RoutineEditor(
                routine,
                onChange = {
                    routine = it
                    RoutineStore.save(context, it)
                },
            )
        }
    }
}

/**
 * Пять строк «название — время»: касание времени открывает выбор. Подъём не позже сна (в одних сутках) — иначе приёмы
 * «поровну днём» считать не из чего: такое время не принимается, под строками причина. [onChange] зовётся только с
 * годным режимом.
 */
@Composable
internal fun RoutineEditor(routine: Routine, onChange: (Routine) -> Unit, modifier: Modifier = Modifier) {
    val texts = rememberMedTexts()
    var editing by rememberSaveable { mutableIntStateOf(-1) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        RoutinePart.entries.forEach { part ->
            val label = stringResource(part.label)
            val time = texts.time(part.get(routine))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    Modifier.weight(1f),
                    color = AnvilTheme.palette.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
                MedValueButton(time, MedIcons.Clock, stringResource(R.string.routine_time_desc, label, time), onClick = { editing = part.ordinal })
            }
        }
        if (invalid) MedNotice(stringResource(R.string.routine_invalid), Modifier.padding(top = 4.dp))
    }

    val part = RoutinePart.entries.getOrNull(editing)
    if (part != null) {
        MedTimeDialog(
            initial = part.get(routine),
            is24 = texts.is24,
            taken = emptySet(),
            title = stringResource(part.label),
            onConfirm = { t ->
                val next = part.set(routine, t)
                invalid = !next.isValid()
                if (!invalid) onChange(next)
                editing = -1
            },
            onDismiss = { editing = -1 },
        )
    }
}
