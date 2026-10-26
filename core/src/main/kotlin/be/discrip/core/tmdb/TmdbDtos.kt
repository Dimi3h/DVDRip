package be.discrip.core.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SearchResponseDto(
    val results: List<MovieSummaryDto> = emptyList(),
)

@Serializable
internal data class MovieSummaryDto(
    val id: Int,
    val title: String = "",
    @SerialName("original_title") val originalTitle: String = "",
    @SerialName("original_language") val originalLanguage: String = "",
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
)

@Serializable
internal data class MovieDetailsDto(
    val id: Int,
    val title: String = "",
    @SerialName("original_title") val originalTitle: String = "",
    @SerialName("original_language") val originalLanguage: String = "",
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val runtime: Int? = null,
    val translations: TranslationsDto? = null,
    @SerialName("external_ids") val externalIds: ExternalIdsDto? = null,
)

@Serializable
internal data class TranslationsDto(
    val translations: List<TranslationDto> = emptyList(),
)

@Serializable
internal data class TranslationDto(
    @SerialName("iso_639_1") val language: String = "",
    @SerialName("iso_3166_1") val country: String = "",
    val data: TranslationDataDto? = null,
)

@Serializable
internal data class TranslationDataDto(
    val title: String? = null,
    val overview: String? = null,
)

@Serializable
internal data class ExternalIdsDto(
    @SerialName("imdb_id") val imdbId: String? = null,
)

@Serializable
internal data class FindResponseDto(
    @SerialName("movie_results") val movieResults: List<MovieSummaryDto> = emptyList(),
)
