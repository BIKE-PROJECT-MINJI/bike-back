package com.bikeprojectminji.bikeback.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GpxTrackParserTest {

    private final GpxTrackParser parser = new GpxTrackParser();

    @Test
    @DisplayName("GPX trkpt 좌표를 course route point 요청으로 변환한다")
    void parseGpxTrackPoints() throws IOException {
        String gpx = fixture("synthetic-normal.gpx");

        var points = parser.parse(gpx);

        assertThat(points).hasSize(3);
        assertThat(points.get(0).pointOrder()).isEqualTo(1);
        assertThat(points.get(0).latitude()).isEqualByComparingTo("37.5010000");
        assertThat(points.get(0).longitude()).isEqualByComparingTo("127.0010000");
        assertThat(points.get(1).pointOrder()).isEqualTo(2);
    }

    @Test
    @DisplayName("trkpt가 없는 GPX fixture는 계약 오류로 거부한다")
    void rejectGpxWithoutTrackPoints() throws IOException {
        assertThatThrownBy(() -> parser.parse(fixture("synthetic-no-track-points.gpx")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("최소 2개");
    }

    @Test
    @DisplayName("XML 구조가 깨진 GPX fixture는 파싱 오류로 거부한다")
    void rejectMalformedGpx() throws IOException {
        assertThatThrownBy(() -> parser.parse(fixture("synthetic-malformed.gpx")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("GPX 파일을 파싱할 수 없습니다.");
    }

    @Test
    @DisplayName("GPX route point가 2개 미만이면 거부한다")
    void rejectGpxWithLessThanTwoTrackPoints() {
        String gpx = """
                <gpx>
                  <trk><trkseg><trkpt lat="37.1" lon="127.1"/></trkseg></trk>
                </gpx>
                """;

        assertThatThrownBy(() -> parser.parse(gpx))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("최소 2개");
    }

    @Test
    @DisplayName("GPX 본문이 너무 크면 파싱 전에 거부한다")
    void rejectTooLargeGpx() {
        String gpx = "<gpx>" + " ".repeat(1_000_001) + "</gpx>";

        assertThatThrownBy(() -> parser.parse(gpx))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("크기");
    }

    @Test
    @DisplayName("GPX 본문은 정확히 1000000자까지 허용한다")
    void acceptExactCharacterLimit() {
        String prefix = "<gpx><trk><trkseg><trkpt lat=\"37.1\" lon=\"127.1\"/>"
                + "<trkpt lat=\"37.2\" lon=\"127.2\"/><!--";
        String suffix = "--></trkseg></trk></gpx>";
        String gpx = prefix + "x".repeat(1_000_000 - prefix.length() - suffix.length()) + suffix;

        assertThat(gpx).hasSize(1_000_000);
        assertThat(parser.parse(gpx)).hasSize(2);
    }

    @Test
    @DisplayName("GPX route point가 5000개를 초과하면 거부한다")
    void rejectTooManyTrackPoints() {
        StringBuilder builder = new StringBuilder("<gpx><trk><trkseg>");
        for (int index = 0; index < 5_001; index++) {
            builder.append("<trkpt lat=\"37.1\" lon=\"127.1\"/>");
        }
        builder.append("</trkseg></trk></gpx>");

        assertThatThrownBy(() -> parser.parse(builder.toString()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("최대 5000개");
    }

    private String fixture(String name) throws IOException {
        String path = "/fixtures/gpx/" + name;
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
