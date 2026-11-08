import Testing
@testable import DiscRipCore

@Suite struct NamingTests {
    @Test func movieWithTMDBId() {
        let m = MediaMetadata(title: "The Matrix", year: 1999, tmdbId: 603)
        #expect(PlexNaming.baseName(m) == "The Matrix (1999) {tmdb-603}")
        #expect(PlexNaming.relativePath(m, moviesBase: "Films", homeVideosBase: "Home Videos")
            == "Films/The Matrix (1999) {tmdb-603}/The Matrix (1999) {tmdb-603}.mkv")
    }

    @Test func movieWithoutId() {
        let m = MediaMetadata(title: "Amélie", year: 2001)
        #expect(PlexNaming.relativePath(m, moviesBase: "", homeVideosBase: "x")
            == "Amélie (2001)/Amélie (2001).mkv")
    }

    @Test func imdbIdWhenNoTMDBId() {
        let m = MediaMetadata(title: "Heat", year: 1995, imdbId: "0113277")
        #expect(PlexNaming.baseName(m) == "Heat (1995) {imdb-tt0113277}")
    }

    @Test func tmdbWinsOverIMDb() {
        let m = MediaMetadata(title: "Heat", year: 1995, tmdbId: 949, imdbId: "tt0113277")
        #expect(PlexNaming.baseName(m) == "Heat (1995) {tmdb-949}")
    }

    @Test func homeVideoUsesOwnFolderAndNoId() {
        let m = MediaMetadata(title: "Zomer in Knokke", year: 2004, tmdbId: 1, kind: .homeVideo)
        #expect(PlexNaming.relativePath(m, moviesBase: "Films", homeVideosBase: "/video/Home Videos/")
            == "video/Home Videos/Zomer in Knokke (2004)/Zomer in Knokke (2004).mkv")
        let other = MediaMetadata(title: "Concert", kind: .other)
        #expect(PlexNaming.relativePath(other, moviesBase: "Films", homeVideosBase: "Overige")
            == "Overige/Concert/Concert.mkv")
    }

    @Test func sanitizesForbiddenCharacters() {
        #expect(PlexNaming.sanitize("Star Wars: Episode IV") == "Star Wars - Episode IV")
        #expect(PlexNaming.sanitize("AC/DC?  Live  ") == "ACDC Live")
        #expect(PlexNaming.sanitize("...") == "DVD")
        #expect(PlexNaming.sanitize("Mr. Nobody.") == "Mr. Nobody")
    }

    @Test func tagsContainTitleDateAndIds() {
        let m = MediaMetadata(title: "The Matrix", year: 1999, tmdbId: 603, imdbId: "tt0133093")
        let tags = Dictionary(PlexNaming.tags(m), uniquingKeysWith: { a, _ in a })
        #expect(tags["title"] == "The Matrix")
        #expect(tags["title-und"] == "The Matrix")
        #expect(tags["DATE_RELEASED"] == "1999")
        #expect(tags["TMDB"] == "movie/603")
        #expect(tags["IMDB"] == "tt0133093")
    }

    @Test func metadataValidation() {
        #expect(MediaMetadata(title: "  ").isValid == false)
        #expect(MediaMetadata(title: "X", year: 1700).isValid == false)
        #expect(MediaMetadata(title: "X", year: 2001).isValid)
        #expect(MediaMetadata(title: "X", imdbId: "tt12a").normalizedIMDbId == nil)
    }
}

@Suite struct TitleNormalizerTests {
    @Test(arguments: [
        ("THE_MATRIX", "The Matrix"),
        ("THE_MATRIX_DISC1", "The Matrix"),
        ("LORD_OF_THE_RINGS_D1", "Lord Of The Rings"),
        ("GLADIATOR_SPECIAL_EDITION", "Gladiator"),
        ("shrek_2", "Shrek 2"),
        ("ROCKY_II", "Rocky II"),
        ("AMELIE_PAL_DISC_2", "Amelie"),
        ("BRAVEHEART_WS_DVD9", "Braveheart"),
        ("Toy.Story.Collectors.Edition", "Toy Story"),
        ("DISC1", "DISC1"),
    ])
    func normalizes(label: String, expected: String) {
        #expect(TitleNormalizer.searchTitle(fromVolumeLabel: label) == expected)
    }
}
