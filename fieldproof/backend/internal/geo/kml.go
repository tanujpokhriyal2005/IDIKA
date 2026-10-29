package geo

import (
	"encoding/xml"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// KMLParcel is one walked boundary written as a Placemark.
type KMLParcel struct {
	Name       string
	Ring       []LatLng // open or closed; written closed and counter-clockwise
	FarmerName string
	FarmerCode string
	Village    string
	SurveyNo   string
	AccuracyM  float64
	WalkedM    float64
	StartedAt  time.Time
	FinishedAt time.Time
}

// FormatCoord writes a degree value with 8 decimals (~1 mm), trimmed.
func FormatCoord(v float64) string {
	s := strconv.FormatFloat(v, 'f', 8, 64)
	s = strings.TrimRight(s, "0")
	return strings.TrimSuffix(s, ".")
}

func esc(s string) string {
	var b strings.Builder
	_ = xml.EscapeText(&b, []byte(s))
	return b.String()
}

// RingCoordinates returns the KML <coordinates> text for a ring: lon,lat,0
// tuples, counter-clockwise, first vertex repeated at the end.
func RingCoordinates(ring []LatLng) string {
	pts := CCW(ring)
	pts = append(pts, pts[0])
	parts := make([]string, len(pts))
	for i, p := range pts {
		parts[i] = FormatCoord(p.Lng) + "," + FormatCoord(p.Lat) + ",0"
	}
	return strings.Join(parts, " ")
}

// BuildKML renders an OGC KML 2.2 document with one polygon Placemark per parcel.
func BuildKML(docName string, parcels []KMLParcel) string {
	var b strings.Builder
	b.WriteString(`<?xml version="1.0" encoding="UTF-8"?>` + "\n")
	b.WriteString(`<kml xmlns="http://www.opengis.net/kml/2.2">` + "\n")
	b.WriteString("<Document>\n")
	fmt.Fprintf(&b, "<name>%s</name>\n", esc(docName))
	b.WriteString(`<Style id="fieldproof-boundary"><LineStyle><color>ff1c50e8</color><width>3</width></LineStyle><PolyStyle><color>4d327d2e</color></PolyStyle></Style>` + "\n")
	for _, p := range parcels {
		area := Area(p.Ring)
		open := openRing(p.Ring)
		b.WriteString("<Placemark>\n")
		fmt.Fprintf(&b, "<name>%s</name>\n", esc(p.Name))
		fmt.Fprintf(&b, "<description>%s</description>\n", esc(fmt.Sprintf(
			"%s (%s), %s. Survey/Gat %s. Area %.2f ac (%.4f ha, %.1f m²). Walked boundary, %d points, GPS ±%.1f m.",
			p.FarmerName, p.FarmerCode, p.Village, p.SurveyNo,
			area/SqMetersPerAcre, area/SqMetersPerHectare, area, len(open), p.AccuracyM)))
		b.WriteString("<styleUrl>#fieldproof-boundary</styleUrl>\n")
		b.WriteString("<ExtendedData>\n")
		data := [][2]string{
			{"farmer_id", p.FarmerCode},
			{"farmer_name", p.FarmerName},
			{"village", p.Village},
			{"survey_no", p.SurveyNo},
			{"area_m2", strconv.FormatFloat(area, 'f', 2, 64)},
			{"area_ha", strconv.FormatFloat(area/SqMetersPerHectare, 'f', 4, 64)},
			{"area_acres", strconv.FormatFloat(area/SqMetersPerAcre, 'f', 4, 64)},
			{"perimeter_m", strconv.FormatFloat(Perimeter(p.Ring), 'f', 1, 64)},
			{"points", strconv.Itoa(len(open))},
			{"gps_accuracy_m", strconv.FormatFloat(p.AccuracyM, 'f', 1, 64)},
			{"walked_m", strconv.FormatFloat(p.WalkedM, 'f', 1, 64)},
		}
		if !p.StartedAt.IsZero() {
			data = append(data, [2]string{"started_at", p.StartedAt.UTC().Format(time.RFC3339)})
		}
		if !p.FinishedAt.IsZero() {
			data = append(data, [2]string{"finished_at", p.FinishedAt.UTC().Format(time.RFC3339)})
		}
		for _, d := range data {
			fmt.Fprintf(&b, "<Data name=\"%s\"><value>%s</value></Data>\n", d[0], esc(d[1]))
		}
		b.WriteString("</ExtendedData>\n")
		b.WriteString("<Polygon><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode><outerBoundaryIs><LinearRing><coordinates>")
		b.WriteString(RingCoordinates(p.Ring))
		b.WriteString("</coordinates></LinearRing></outerBoundaryIs></Polygon>\n")
		b.WriteString("</Placemark>\n")
	}
	b.WriteString("</Document>\n</kml>\n")
	return b.String()
}

// ParseKMLRings reads every LinearRing back out of a KML document. Used by
// tests to prove the written file round-trips exactly.
func ParseKMLRings(doc string) ([][]LatLng, error) {
	dec := xml.NewDecoder(strings.NewReader(doc))
	var rings [][]LatLng
	inCoords := false
	inRing := false
	for {
		tok, err := dec.Token()
		if err != nil {
			if err.Error() == "EOF" {
				break
			}
			return nil, err
		}
		switch t := tok.(type) {
		case xml.StartElement:
			if t.Name.Local == "LinearRing" {
				inRing = true
			}
			if t.Name.Local == "coordinates" && inRing {
				inCoords = true
			}
		case xml.EndElement:
			if t.Name.Local == "LinearRing" {
				inRing = false
			}
			if t.Name.Local == "coordinates" {
				inCoords = false
			}
		case xml.CharData:
			if !inCoords {
				continue
			}
			var ring []LatLng
			for _, tuple := range strings.Fields(string(t)) {
				f := strings.Split(tuple, ",")
				if len(f) < 2 {
					return nil, fmt.Errorf("bad tuple %q", tuple)
				}
				lng, err1 := strconv.ParseFloat(f[0], 64)
				lat, err2 := strconv.ParseFloat(f[1], 64)
				if err1 != nil || err2 != nil {
					return nil, fmt.Errorf("bad tuple %q", tuple)
				}
				ring = append(ring, LatLng{lat, lng})
			}
			rings = append(rings, ring)
		}
	}
	return rings, nil
}
