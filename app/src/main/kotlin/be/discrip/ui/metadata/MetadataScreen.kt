package be.discrip.ui.metadata

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import be.discrip.AppContainer
import be.discrip.R
import be.discrip.core.model.MediaKind
import be.discrip.core.model.MetadataMode
import be.discrip.core.model.MetadataProblem
import be.discrip.core.tmdb.TmdbClient
import be.discrip.core.tmdb.TmdbMovie
import be.discrip.ui.components.ActivityIndicator
import be.discrip.ui.components.BarButton
import be.discrip.ui.components.ButtonRow
import be.discrip.ui.components.FilledButton
import be.discrip.ui.components.GroupedSection
import be.discrip.ui.components.HigScaffold
import be.discrip.ui.components.NavigationRow
import be.discrip.ui.components.Poster
import be.discrip.ui.components.RowFrame
import be.discrip.ui.components.SearchField
import be.discrip.ui.components.SectionContent
import be.discrip.ui.components.SegmentedControl
import be.discrip.ui.components.TextFieldRow
import be.discrip.ui.components.rememberHaptics
import be.discrip.ui.icons.HigIcons
import be.discrip.ui.labelRes
import be.discrip.ui.theme.HigTheme

private val PosterWidth = 46.dp
private val ResultSeparatorInset = 16.dp + PosterWidth + 14.dp

@Composable
fun MetadataScreen(
    container: AppContainer,
    volumeLabel: String?,
    backLabel: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel = viewModel { MetadataViewModel(container, volumeLabel) }
    val state = viewModel.state
    val haptics = rememberHaptics()
    val confirm = {
        if (viewModel.confirm()) {
            haptics.success()
            onDone()
        } else {
            haptics.failure()
        }
    }

    HigScaffold(
        title = stringResource(R.string.metadata_title),
        largeTitle = false,
        backLabel = backLabel,
        onBack = onBack,
        actions = {
            BarButton(stringResource(R.string.action_done), onClick = confirm, enabled = state.canConfirm, prominent = true)
        },
    ) {
        if (state.loading) return@HigScaffold

        SegmentedControl(
            options = MetadataMode.entries.map { stringResource(it.labelRes()) },
            selectedIndex = state.mode.ordinal,
            onSelect = { viewModel.setMode(MetadataMode.entries[it]) },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )

        when (state.mode) {
            MetadataMode.AUTOMATIC -> AutomaticContent(state, viewModel, onOpenSettings)
            MetadataMode.MANUAL -> if (state.fellBackToManual) {
                GroupedSection(footer = stringResource(R.string.metadata_fell_back), footerColor = HigTheme.colors.orange) {
                    row { ButtonRow(stringResource(R.string.action_retry_tmdb), onClick = { viewModel.setMode(MetadataMode.AUTOMATIC) }) }
                }
            }
        }

        if (state.showForm) {
            FormSections(state, viewModel)
            PreviewSection(state)
            FilledButton(
                text = stringResource(R.string.metadata_use),
                onClick = confirm,
                enabled = state.canConfirm,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 32.dp),
            )
        }
    }
}

@Composable
private fun AutomaticContent(state: MetadataUiState, viewModel: MetadataViewModel, onOpenSettings: () -> Unit) {
    val colors = HigTheme.colors
    if (!state.hasTmdbKey) {
        GroupedSection(footer = stringResource(R.string.metadata_no_key)) {
            row { ButtonRow(stringResource(R.string.action_enter_manually), onClick = { viewModel.setMode(MetadataMode.MANUAL) }) }
            row { NavigationRow(stringResource(R.string.action_open_settings), onClick = onOpenSettings) }
        }
        return
    }

    SearchField(
        value = state.query,
        onValueChange = viewModel::onQueryChange,
        onSearch = { viewModel.search() },
        placeholder = stringResource(R.string.metadata_search_placeholder),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
    )
    GroupedSection(footer = stringResource(R.string.metadata_year_filter_footer)) {
        row {
            TextFieldRow(
                label = stringResource(R.string.field_year),
                value = state.yearFilter,
                onValueChange = viewModel::onYearFilterChange,
                placeholder = stringResource(R.string.metadata_year_filter_placeholder),
                keyboardType = KeyboardType.Number,
            )
        }
    }

    when (val search = state.search) {
        SearchState.Idle -> Hint(stringResource(R.string.metadata_search_hint))
        SearchState.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            ActivityIndicator(Modifier.size(24.dp))
        }
        is SearchState.Failed -> GroupedSection(footer = stringResource(search.problem.messageRes()), footerColor = colors.red) {
            row { ButtonRow(stringResource(R.string.action_retry), onClick = { viewModel.search() }) }
            row { ButtonRow(stringResource(R.string.action_enter_manually), onClick = { viewModel.setMode(MetadataMode.MANUAL) }) }
            if (search.problem == TmdbProblem.INVALID_KEY) {
                row { NavigationRow(stringResource(R.string.action_open_settings), onClick = onOpenSettings) }
            }
        }
        is SearchState.Results -> if (search.movies.isEmpty()) {
            GroupedSection(footer = stringResource(R.string.metadata_no_results, search.query)) {
                row { ButtonRow(stringResource(R.string.action_enter_manually), onClick = { viewModel.setMode(MetadataMode.MANUAL) }) }
            }
        } else {
            GroupedSection(header = stringResource(R.string.metadata_results_header)) {
                search.movies.forEach { movie ->
                    row(ResultSeparatorInset) {
                        ResultRow(movie, selected = movie.id == state.selectedTmdbId, onClick = { viewModel.select(movie) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(movie: TmdbMovie, selected: Boolean, onClick: () -> Unit) {
    val colors = HigTheme.colors
    val typography = HigTheme.typography
    RowFrame(onClick = onClick, role = Role.RadioButton) {
        Poster(TmdbClient.posterUrl(movie.posterPath), width = PosterWidth, modifier = Modifier.padding(vertical = 8.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = movie.title,
                style = typography.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(movie.year?.toString(), movie.originalTitle.takeIf { it != movie.title })
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), style = typography.subheadline, color = colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (movie.overview.isNotBlank()) {
                Text(movie.overview, style = typography.footnote, color = colors.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(HigIcons.Checkmark, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun FormSections(state: MetadataUiState, viewModel: MetadataViewModel) {
    val colors = HigTheme.colors
    val form = state.form
    val manual = state.mode == MetadataMode.MANUAL
    val problems = if (state.showProblems) state.validation.problems else emptySet()
    val problemText = problems.sortedBy { it.ordinal }.map { stringResource(it.messageRes()) }.joinToString("\n")

    GroupedSection(
        header = stringResource(R.string.metadata_details_header),
        footer = when {
            problemText.isNotEmpty() -> problemText
            !manual && state.loadingDetails -> stringResource(R.string.metadata_loading_details)
            !manual -> stringResource(R.string.metadata_selected_footer)
            else -> null
        },
        footerColor = if (problemText.isNotEmpty()) colors.red else null,
    ) {
        row {
            TextFieldRow(
                label = stringResource(R.string.field_title),
                value = form.title,
                onValueChange = { value -> viewModel.updateForm { it.copy(title = value) } },
                placeholder = stringResource(R.string.placeholder_required),
                capitalization = KeyboardCapitalization.Words,
                labelWidth = 72.dp,
            )
        }
        row {
            TextFieldRow(
                label = stringResource(R.string.field_year),
                value = form.year,
                onValueChange = { value -> viewModel.updateForm { it.copy(year = value.filter(Char::isDigit).take(4)) } },
                placeholder = stringResource(if (form.kind.usesMovieIds) R.string.placeholder_required else R.string.placeholder_optional),
                keyboardType = KeyboardType.Number,
                labelWidth = 72.dp,
            )
        }
    }

    if (!manual) return

    GroupedSection(
        header = stringResource(R.string.field_kind),
        footer = if (form.kind.usesMovieIds) null else stringResource(R.string.metadata_home_video_footer),
    ) {
        row {
            SectionContent {
                SegmentedControl(
                    options = MediaKind.entries.map { stringResource(it.labelRes()) },
                    selectedIndex = form.kind.ordinal,
                    onSelect = { index -> viewModel.updateForm { it.copy(kind = MediaKind.entries[index]) } },
                )
            }
        }
    }

    if (form.kind.usesMovieIds) {
        GroupedSection(
            header = stringResource(R.string.metadata_ids_header),
            footer = stringResource(R.string.metadata_ids_footer),
        ) {
            row {
                TextFieldRow(
                    label = stringResource(R.string.field_tmdb_id),
                    value = form.tmdbId,
                    onValueChange = { value -> viewModel.updateForm { it.copy(tmdbId = value.trim()) } },
                    placeholder = stringResource(R.string.placeholder_optional),
                    keyboardType = KeyboardType.Uri,
                    labelWidth = 96.dp,
                )
            }
            row {
                TextFieldRow(
                    label = stringResource(R.string.field_imdb_id),
                    value = form.imdbId,
                    onValueChange = { value -> viewModel.updateForm { it.copy(imdbId = value.trim()) } },
                    placeholder = stringResource(R.string.field_imdb_id_placeholder),
                    keyboardType = KeyboardType.Uri,
                    labelWidth = 96.dp,
                )
            }
        }
    }
}

@Composable
private fun PreviewSection(state: MetadataUiState) {
    val preview = state.preview ?: return
    val colors = HigTheme.colors
    val typography = HigTheme.typography
    val inLocalFolder = state.settings.defaultStorage.usesLocalFolder
    GroupedSection(
        header = stringResource(R.string.metadata_preview_header),
        footer = stringResource(if (inLocalFolder) R.string.metadata_preview_footer_local else R.string.metadata_preview_footer_nas),
    ) {
        listOf(
            R.string.metadata_preview_folder to preview.relativeFolder,
            R.string.metadata_preview_name to preview.fileName,
        ).forEach { (label, value) ->
            row {
                RowFrame {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(stringResource(label), style = typography.subheadline, color = colors.secondaryLabel)
                        Text(value, style = typography.body, color = colors.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = HigTheme.typography.footnote,
        color = HigTheme.colors.secondaryLabel,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
    )
}

private fun TmdbProblem.messageRes(): Int = when (this) {
    TmdbProblem.UNREACHABLE -> R.string.tmdb_error_unreachable
    TmdbProblem.INVALID_KEY -> R.string.tmdb_error_key
    TmdbProblem.RATE_LIMITED -> R.string.tmdb_error_rate_limited
    TmdbProblem.OTHER -> R.string.tmdb_error_other
}

private fun MetadataProblem.messageRes(): Int = when (this) {
    MetadataProblem.TITLE_MISSING -> R.string.problem_title_missing
    MetadataProblem.TITLE_UNUSABLE -> R.string.problem_title_unusable
    MetadataProblem.YEAR_MISSING -> R.string.problem_year_missing
    MetadataProblem.YEAR_INVALID -> R.string.problem_year_invalid
    MetadataProblem.TMDB_ID_INVALID -> R.string.problem_tmdb_id_invalid
    MetadataProblem.IMDB_ID_INVALID -> R.string.problem_imdb_id_invalid
}
