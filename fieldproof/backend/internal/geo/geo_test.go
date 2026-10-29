package geo

import (
	"encoding/xml"
	"math"
	"strings"
	"testing"
)

// unproject is the inverse of ENU.Project for points on the tangent plane,
// dropped back to the ellipsoid surface. Test-only helper.
func (t ENU) unproject(p XY) LatLng {
	// ENU -> ECEF (U = 0)
	x := t.x0 - t.sinL*p.E - t.sinP*t.cosL*p.N
	y := t.y0 + t.cosL*p.E - t.sinP*t.sinL*p.N
	z := t.z0 + t.cosP*p.N
	// ECEF -> geodetic, iterate to convergence.
	lng := math.Atan2(y, x)
	r := math.Hypot(x, y)
	lat := math.Atan2(z, r*(1-wgsE2))
	for i := 0; i < 10; i++ {
		s := math.Sin(lat)
		n := wgsA / math.Sqrt(1-wgsE2*s*s)
		h := r/math.Cos(lat) - n
		lat = math.Atan2(z, r*(1-wgsE2*n/(n+h)))
	}
	return LatLng{lat * 180 / math.Pi, lng * 180 / math.Pi}
}

var hingoli = LatLng{19.5651, 77.0482}

func shape(origin LatLng, xy []XY) []LatLng {
	t := NewENU(origin)
	out := make([]LatLng, len(xy))
	for i, p := range xy {
		out[i] = t.unproject(p)
	}
	return out
}

func rect(w, h float64) []XY {
	return []XY{{0, 0}, {w, 0}, {w, h}, {0, h}}
}

func TestDistance(t *testing.T) {
	pts := shape(hingoli, []XY{{0, 0}, {3, 4}})
	if d := Distance(pts[0], pts[1]); math.Abs(d-5) > 1e-4 {
		t.Fatalf("distance = %.6f, want 5", d)
	}
	// One arc-second of latitude at 19.5°N is ~30.73 m on WGS84.
	d := Distance(LatLng{19.5, 77}, LatLng{19.5 + 1.0/3600, 77})
	if math.Abs(d-30.73) > 0.02 {
		t.Fatalf("1 arcsec lat = %.4f m", d)
	}
}

func TestAreaRectangle(t *testing.T) {
	// 1.50 acres = 6070.28 m² as a 77.91 m square.
	side := math.Sqrt(1.5 * SqMetersPerAcre)
	ring := shape(hingoli, rect(side, side))
	got := Area(ring)
	if math.Abs(got-1.5*SqMetersPerAcre) > 0.01 {
		t.Fatalf("area = %.4f, want %.4f", got, 1.5*SqMetersPerAcre)
	}
	if ac := got / SqMetersPerAcre; math.Abs(ac-1.5) > 1e-5 {
		t.Fatalf("acres = %.6f", ac)
	}
}

func TestAreaIndependentOfOrientationAndClosure(t *testing.T) {
	ring := shape(hingoli, []XY{{0, 0}, {120, 10}, {140, 90}, {60, 130}, {-10, 70}})
	a := Area(ring)
	rev := append([]LatLng(nil), ring...)
	for i, j := 0, len(rev)-1; i < j; i, j = i+1, j-1 {
		rev[i], rev[j] = rev[j], rev[i]
	}
	closed := append(append([]LatLng(nil), ring...), ring[0])
	if math.Abs(Area(rev)-a) > 1e-6 || math.Abs(Area(closed)-a) > 1e-6 {
		t.Fatal("area changed with orientation or closure")
	}
	if SignedArea(ring) <= 0 || SignedArea(rev) >= 0 {
		t.Fatal("orientation sign wrong")
	}
	// Shoelace of the known plane polygon.
	want := shoelace([]XY{{0, 0}, {120, 10}, {140, 90}, {60, 130}, {-10, 70}})
	if math.Abs(a-want) > 0.01 {
		t.Fatalf("area %.4f want %.4f", a, want)
	}
}

func TestSelfIntersection(t *testing.T) {
	bowtie := shape(hingoli, []XY{{0, 0}, {100, 100}, {100, 0}, {0, 100}})
	if !SelfIntersects(bowtie) {
		t.Fatal("bow-tie not detected")
	}
	if SelfIntersects(shape(hingoli, rect(80, 50))) {
		t.Fatal("rectangle flagged")
	}
	// Concave but simple "C" shape must pass.
	c := shape(hingoli, []XY{{0, 0}, {60, 0}, {60, 15}, {15, 15}, {15, 45}, {60, 45}, {60, 60}, {0, 60}})
	if SelfIntersects(c) {
		t.Fatal("concave simple polygon flagged")
	}
	// Ring that touches itself at a vertex is not simple.
	touch := shape(hingoli, []XY{{0, 0}, {50, 0}, {25, 25}, {50, 50}, {0, 50}, {25, 25}})
	if !SelfIntersects(touch) {
		t.Fatal("vertex touch not detected")
	}
}

func dense(xy []XY, step float64) []XY {
	var out []XY
	for i := range xy {
		a, b := xy[i], xy[(i+1)%len(xy)]
		l := math.Hypot(b.E-a.E, b.N-a.N)
		n := int(math.Ceil(l / step))
		for k := 0; k < n; k++ {
			f := float64(k) / float64(n)
			out = append(out, XY{a.E + f*(b.E-a.E), a.N + f*(b.N-a.N)})
		}
	}
	return out
}

func TestValidateRing(t *testing.T) {
	if err := ValidateRing(shape(hingoli, rect(50, 50))); err != ErrTooFewPoints {
		t.Fatalf("want too few points, got %v", err)
	}
	if err := ValidateRing(shape(hingoli, dense(rect(50, 50), 2))); err != nil {
		t.Fatal(err)
	}
}

func TestKMLRoundTripAndStructure(t *testing.T) {
	walk := shape(hingoli, dense([]XY{{0, 0}, {90, 5}, {95, 70}, {-5, 66}}, 2))
	// Clockwise input: KML must come out counter-clockwise.
	cw := append([]LatLng(nil), walk...)
	for i, j := 0, len(cw)-1; i < j; i, j = i+1, j-1 {
		cw[i], cw[j] = cw[j], cw[i]
	}
	doc := BuildKML("Sunanda Ramrao Raut · 4417sun", []KMLParcel{{
		Name: "4417sun · Gat 214/2B", Ring: cw, FarmerName: "Sunanda Ramrao Raut & <test>",
		FarmerCode: "4417sun", Village: "Kurunda", SurveyNo: "214/2B", AccuracyM: 3,
	}})
	// Well-formed XML.
	dec := xml.NewDecoder(strings.NewReader(doc))
	for {
		if _, err := dec.Token(); err != nil {
			if err.Error() != "EOF" {
				t.Fatalf("not well-formed: %v", err)
			}
			break
		}
	}
	rings, err := ParseKMLRings(doc)
	if err != nil || len(rings) != 1 {
		t.Fatalf("parse: %v %d", err, len(rings))
	}
	r := rings[0]
	if r[0] != r[len(r)-1] {
		t.Fatal("ring not closed")
	}
	if len(r) != len(walk)+1 {
		t.Fatalf("vertex count %d want %d", len(r), len(walk)+1)
	}
	if SignedArea(r) <= 0 {
		t.Fatal("KML ring is not counter-clockwise")
	}
	// Every written vertex is within 1e-8° (≈1 mm) of an input vertex.
	for _, p := range r {
		best := math.Inf(1)
		for _, q := range walk {
			best = math.Min(best, Distance(p, q))
		}
		if best > 0.002 {
			t.Fatalf("vertex moved %.4f m", best)
		}
	}
	if d := math.Abs(Area(r) - Area(walk)); d > 0.05 {
		t.Fatalf("area drift %.4f m²", d)
	}
	for _, must := range []string{
		`<kml xmlns="http://www.opengis.net/kml/2.2">`,
		"<altitudeMode>clampToGround</altitudeMode>",
		"&amp; &lt;test&gt;",
		`<Data name="area_acres">`,
	} {
		if !strings.Contains(doc, must) {
			t.Fatalf("missing %q", must)
		}
	}
	if strings.Contains(doc, "77.0482,19.5651") == false && !strings.Contains(doc, ",19.5") {
		t.Fatal("coordinates not lon,lat order")
	}
}

func TestFormatCoord(t *testing.T) {
	cases := map[float64]string{77.04820000: "77.0482", 19.123456789: "19.12345679", -0.5: "-0.5", 10: "10"}
	for in, want := range cases {
		if got := FormatCoord(in); got != want {
			t.Errorf("FormatCoord(%v) = %s want %s", in, got, want)
		}
	}
}
