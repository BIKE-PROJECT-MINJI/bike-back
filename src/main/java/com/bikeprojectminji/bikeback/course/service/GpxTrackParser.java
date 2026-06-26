package com.bikeprojectminji.bikeback.course.service;

import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointRequest;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

public class GpxTrackParser {

    private static final int MAX_GPX_CHARS = 1_000_000;
    private static final int MAX_TRACK_POINTS = 5_000;

    public List<CourseRoutePointRequest> parse(String gpx) {
        if (gpx == null || gpx.isBlank()) {
            throw new BadRequestException("gpx는 비어 있을 수 없습니다.");
        }
        if (gpx.length() > MAX_GPX_CHARS) {
            throw new BadRequestException("GPX 파일 크기가 허용 범위를 초과했습니다.");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setNamespaceAware(false);
            var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(gpx)));
            var nodes = document.getElementsByTagName("trkpt");
            if (nodes.getLength() > MAX_TRACK_POINTS) {
                throw new BadRequestException("GPX route point는 최대 5000개까지 허용됩니다.");
            }
            List<CourseRoutePointRequest> points = new ArrayList<>();
            for (int index = 0; index < nodes.getLength(); index++) {
                Element element = (Element) nodes.item(index);
                points.add(new CourseRoutePointRequest(
                        index + 1,
                        new BigDecimal(element.getAttribute("lat")),
                        new BigDecimal(element.getAttribute("lon"))
                ));
            }
            if (points.size() < 2) {
                throw new BadRequestException("GPX에는 trkpt 좌표가 최소 2개 필요합니다.");
            }
            return points;
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BadRequestException("GPX 파일을 파싱할 수 없습니다.");
        }
    }
}
