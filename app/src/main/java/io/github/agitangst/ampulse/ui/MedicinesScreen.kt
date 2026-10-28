package io.github.agitangst.ampulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.AmpulseIcons
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.EmptyState
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.PageLayout
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.TabHeader
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.PlanKind
import io.github.agitangst.anvil.compose.AnvilTheme

/**
 * «Аптечка»: список лекарств со сводкой схемы словами; касание — правка, у «по необходимости» справа ещё «Принять»
 * (таймер и счётчик). Запас и сроки годности — этап 0.4.
 */
@Composable
fun MedicinesScreen(nav: Nav, modifier: Modifier = Modifier) {
    val repo = AppGraph.repository
    val medicines by remember { repo.medicines }.collectAsState(initial = null)
    val schedules by remember { repo.schedules }.collectAsState(initial = null)
    val texts = rememberMedTexts()
    val add = stringResource(R.string.meds_add)
    PageLayout(
        modifier,
        header = {
            TabHeader(
                stringResource(R.string.tab_medicines),
                action = { MedRoundButton(MedIcons.Plus, add, onClick = { nav.open(Route.MedicineEdit(0)) }) },
            )
        },
    ) {
        val list = medicines
        val plans = schedules
        when {
            // Данные ещё читаются: пустую «Аптечку» не мигаем.
            list == null || plans == null -> Box(Modifier.weight(1f))
            list.isEmpty() -> {
                EmptyState(
                    AmpulseIcons.BriefcaseMedical,
                    stringResource(R.string.medicines_empty_title),
                    stringResource(R.string.medicines_empty_hint),
                    Modifier.weight(1f),
                )
                MedMainButton(add, onClick = { nav.open(Route.MedicineEdit(0)) })
            }
            else -> {
                val planOf = plans.associate { it.medicineId to it.plan }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.id }) { med ->
                        val plan = planOf[med.id]
                        MedicineRow(
                            med,
                            plan,
                            texts.summary(plan, med.unit),
                            onOpen = { nav.open(Route.MedicineEdit(med.id)) },
                            onTake = { nav.open(Route.AsNeeded(med.id)) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Строка списка (docs/ui-guideline.md, §6): плитка значка формы 40×40 → имя с дозировкой → сводка схемы → справа
 * действие. У «по необходимости» это «Принять», у остальных — стрелка.
 */
@Composable
private fun MedicineRow(med: Medicine, plan: Plan?, summary: String, onOpen: () -> Unit, onTake: () -> Unit) {
    val p = AnvilTheme.palette
    val paused = plan?.paused == true
    val title = if (med.strength.isBlank()) med.name else "${med.name} ${med.strength}"
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.card)
            .heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 64.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Ampulse.colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MedIcons.of(med.form), contentDescription = null, modifier = Modifier.size(22.dp), tint = p.accentText)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    color = p.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    summary,
                    color = if (paused) p.warning else p.weak,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (plan?.kind == PlanKind.AsNeeded && !paused) {
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Ampulse.colors.accentSoft)
                    .clickable(role = Role.Button, onClick = onTake)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.meds_prn_take),
                    color = p.accentText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        } else {
            Icon(
                MedIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp).size(20.dp),
                tint = p.faint,
            )
        }
    }
}
