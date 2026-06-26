package com.bikeprojectminji.bikeback.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GpxTrackParserTest {

    private final GpxTrackParser parser = new GpxTrackParser();

    @Test
    @DisplayName("GPX trkpt 좌표를 course route point 요청으로 변환한다")
    void parseGpxTrackPoints() {
        String gpx = """
                <?xml version="1.0" encoding="UTF-8"?>
                <gpx version="1.1">
                  <trk>
                    <trkseg>
                      <trkpt lat="37.4813850" lon="126.9527790"><ele>43</ele></trkpt>
                      <trkpt lat="37.4819100" lon="126.9533200"><ele>45</ele></trkpt>
                    </trkseg>
                  </trk>
                </gpx>
                """;

        var points = parser.parse(gpx);

        assertThat(points).hasSize(2);
        assertThat(points.get(0).pointOrder()).isEqualTo(1);
        assertThat(points.get(0).latitude()).isEqualByComparingTo("37.4813850");
        assertThat(points.get(0).longitude()).isEqualByComparingTo("126.9527790");
        assertThat(points.get(1).pointOrder()).isEqualTo(2);
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
}
