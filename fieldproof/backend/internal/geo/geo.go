// Package geo holds the boundary geometry used to validate walked farm
// boundaries: WGS84 -> local tangent plane projection, geodesic-grade area,
// ring orientation and self-intersection tests. The Android app carries a
// line-for-line port of this file (Geo.kt); keep the two in step.
package geo

import (
	"errors"
	"math"
)

// WGS84 ellipsoid.
const (
	wgsA  = 6378137.0
	wgsF  = 1 / 298.257223563
	wgsE2 = wgsF * (2 - wgsF)

	SqMetersPerAcre    = 4046.8564224
	SqMetersPerHectare = 10000.0
)

type LatLng struct {
	Lat float64 `json:"lat"`
	Lng float64 `json:"lng"`
}

type XY struct{ E, N float64 }

func rad(d float64) float64 { return d * math.Pi / 180 }

// ecef converts a point on the ellipsoid surface (h = 0) to earth-centred coordinates.
func ecef(p LatLng) (x, y, z float64) {
	phi, lam := rad(p.Lat), rad(p.Lng)
	s := math.Sin(phi)
	n := wgsA / math.Sqrt(1-wgsE2*s*s)
	x = n * math.Cos(phi) * math.Cos(lam)
	y = n * math.Cos(phi) * math.Sin(lam)
	z = n * (1 - wgsE2) * s
	return
}

// Distance is the straight-line distance in metres between two surface points.
// For the separations we care about (< a few km) it equals the geodesic
// distance to well under a millimetre.
func Distance(a, b LatLng) float64 {
	ax, ay, az := ecef(a)
	bx, by, bz := ecef(b)
	return math.Sqrt((ax-bx)*(ax-bx) + (ay-by)*(ay-by) + (az-bz)*(az-bz))
}

// ENU is a local East-North-Up tangent plane anchored at an origin.
type ENU struct {
	x0, y0, z0             float64
	sinP, cosP, sinL, cosL float64
}

func NewENU(origin LatLng) ENU {
	x0, y0, z0 := ecef(origin)
	phi, lam := rad(origin.Lat), rad(origin.Lng)
	return ENU{x0, y0, z0, math.Sin(phi), math.Cos(phi), math.Sin(lam), math.Cos(lam)}
}

func (t ENU) Project(p LatLng) XY {
	x, y, z := ecef(p)
	dx, dy, dz := x-t.x0, y-t.y0, z-t.z0
	return XY{
		E: -t.sinL*dx + t.cosL*dy,
		N: -t.sinP*t.cosL*dx - t.sinP*t.sinL*dy + t.cosP*dz,
	}
}

// Centroid of the vertices (plain average; used only as a projection origin).
func Centroid(pts []LatLng) LatLng {
	var c LatLng
	for _, p := range pts {
		c.Lat += p.Lat
		c.Lng += p.Lng
	}
	n := float64(len(pts))
	return LatLng{c.Lat / n, c.Lng / n}
}

// openRing drops a duplicated closing vertex if present.
func openRing(pts []LatLng) []LatLng {
	if len(pts) > 1 && pts[0] == pts[len(pts)-1] {
		return pts[:len(pts)-1]
	}
	return pts
}

func ProjectRing(pts []LatLng) []XY {
	pts = openRing(pts)
	t := NewENU(Centroid(pts))
	out := make([]XY, len(pts))
	for i, p := range pts {
		out[i] = t.Project(p)
	}
	return out
}

// SignedArea in m², positive when the ring runs counter-clockwise.
func SignedArea(pts []LatLng) float64 {
	xy := ProjectRing(pts)
	return shoelace(xy)
}

func shoelace(xy []XY) float64 {
	s := 0.0
	for i := range xy {
		j := (i + 1) % len(xy)
		s += xy[i].E*xy[j].N - xy[j].E*xy[i].N
	}
	return s / 2
}

// Area of the ring in m². Error versus the true ellipsoidal area is below
// 1e-6 relative for fields up to several km across.
func Area(pts []LatLng) float64 { return math.Abs(SignedArea(pts)) }

func Perimeter(pts []LatLng) float64 {
	pts = openRing(pts)
	s := 0.0
	for i := range pts {
		s += Distance(pts[i], pts[(i+1)%len(pts)])
	}
	return s
}

// CCW returns the open ring in counter-clockwise order (KML outer-ring convention).
func CCW(pts []LatLng) []LatLng {
	pts = append([]LatLng(nil), openRing(pts)...)
	if SignedArea(pts) < 0 {
		for i, j := 0, len(pts)-1; i < j; i, j = i+1, j-1 {
			pts[i], pts[j] = pts[j], pts[i]
		}
	}
	return pts
}

func orient(a, b, c XY) float64 { return (b.E-a.E)*(c.N-a.N) - (b.N-a.N)*(c.E-a.E) }

func onSegment(a, b, p XY) bool {
	return math.Min(a.E, b.E)-1e-9 <= p.E && p.E <= math.Max(a.E, b.E)+1e-9 &&
		math.Min(a.N, b.N)-1e-9 <= p.N && p.N <= math.Max(a.N, b.N)+1e-9
}

// SegmentsIntersect reports whether closed segments ab and cd share any point.
func SegmentsIntersect(a, b, c, d XY) bool {
	const eps = 1e-9
	d1, d2 := orient(c, d, a), orient(c, d, b)
	d3, d4 := orient(a, b, c), orient(a, b, d)
	if ((d1 > eps && d2 < -eps) || (d1 < -eps && d2 > eps)) &&
		((d3 > eps && d4 < -eps) || (d3 < -eps && d4 > eps)) {
		return true
	}
	if math.Abs(d1) <= eps && onSegment(c, d, a) {
		return true
	}
	if math.Abs(d2) <= eps && onSegment(c, d, b) {
		return true
	}
	if math.Abs(d3) <= eps && onSegment(a, b, c) {
		return true
	}
	if math.Abs(d4) <= eps && onSegment(a, b, d) {
		return true
	}
	return false
}

// SelfIntersects reports whether any two non-adjacent edges of the closed ring touch.
func SelfIntersects(pts []LatLng) bool {
	xy := ProjectRing(pts)
	n := len(xy)
	if n < 4 {
		return false
	}
	for i := 0; i < n; i++ {
		a, b := xy[i], xy[(i+1)%n]
		for j := i + 2; j < n; j++ {
			if i == 0 && j == n-1 {
				continue // first and last edge share vertex 0
			}
			if SegmentsIntersect(a, b, xy[j], xy[(j+1)%n]) {
				return true
			}
		}
	}
	return false
}

var (
	ErrTooFewPoints  = errors.New("boundary needs at least 12 points")
	ErrSelfIntersect = errors.New("boundary crosses itself")
	ErrBadCoordinate = errors.New("coordinate out of range")
)

const MinPoints = 12

// ValidateRing checks a walked ring (open or closed) the way the app does.
func ValidateRing(pts []LatLng) error {
	pts = openRing(pts)
	if len(pts) < MinPoints {
		return ErrTooFewPoints
	}
	for _, p := range pts {
		if math.IsNaN(p.Lat) || math.IsNaN(p.Lng) || p.Lat < -90 || p.Lat > 90 || p.Lng < -180 || p.Lng > 180 {
			return ErrBadCoordinate
		}
	}
	if SelfIntersects(pts) {
		return ErrSelfIntersect
	}
	return nil
}
