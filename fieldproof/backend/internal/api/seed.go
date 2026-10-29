package api

import (
	"context"
	"fmt"
	"log"
	"math/rand"

	"github.com/jackc/pgx/v5/pgxpool"
)

var seedVillages = map[string][]string{
	"Kalamnuri": {"Akhada Balapur", "Belthar", "Dongarkada", "Jamb", "Kalamnuri", "Masod", "Nandapur", "Pota", "Warud"},
	"Basmat":    {"Girgaon", "Hatta", "Kauthala", "Kurunda", "Parli", "Rajapur", "Wapti"},
}

var firstNames = []string{"Rameshwar", "Sunil", "Vithal", "Dnyaneshwar", "Balaji", "Shankar", "Prakash", "Gajanan", "Sopan", "Maroti",
	"Savita", "Kavita", "Suman", "Lata", "Parvati", "Datta", "Bhagwan", "Narayan", "Uttam", "Pandurang", "Anita", "Sangita", "Vishnu", "Tukaram"}
var lastNames = []string{"Jadhav", "Patil", "Pawar", "Shinde", "Kadam", "Bhosale", "Gaikwad", "Deshmukh", "More", "Chavan", "Wagh",
	"Kale", "Mundhe", "Sawant", "Thorat", "Gore", "Kharat", "Bhise", "Rathod", "Dhage"}

// Seed fills an empty database with the demo surveyor (Tanuj, 98765 43210)
// and the farmers shown on the Home screen design.
func Seed(ctx context.Context, db *pgxpool.Pool) error {
	var n int
	if err := db.QueryRow(ctx, `SELECT count(*) FROM surveyors`).Scan(&n); err != nil || n > 0 {
		return err
	}
	log.Print("seeding demo data")
	tx, err := db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	villageID := map[string]int{}
	for block, names := range seedVillages {
		for _, v := range names {
			var id int
			if err := tx.QueryRow(ctx, `INSERT INTO villages(district, block, name) VALUES ('Hingoli',$1,$2) RETURNING id`, block, v).Scan(&id); err != nil {
				return err
			}
			villageID[v] = id
		}
	}
	var sid string
	if err := tx.QueryRow(ctx, `INSERT INTO surveyors(phone, name, district, home_block, language) VALUES ('9876543210','Tanuj','Hingoli','Kalamnuri','en') RETURNING id`).Scan(&sid); err != nil {
		return err
	}
	for block := range seedVillages {
		if _, err := tx.Exec(ctx, `INSERT INTO surveyor_blocks VALUES ($1,'Hingoli',$2)`, sid, block); err != nil {
			return err
		}
	}

	addFarmer := func(number int, name, mobile, village, status string, lands [][3]any) (string, error) {
		var id string
		err := tx.QueryRow(ctx, `
			INSERT INTO farmers(id, number, code, surveyor_id, full_name, mobile, whatsapp_same, whatsapp, village_id, tree_interest,
				aadhaar_name_match, aadhaar_consent, status)
			VALUES (gen_random_uuid(), $1, $2, $3, $4, $5, true, $5, $6, true, true, true, $7) RETURNING id`,
			number, FarmerCode(number, name), sid, name, mobile, villageID[village], status).Scan(&id)
		if err != nil {
			return "", err
		}
		if _, err := tx.Exec(ctx, `INSERT INTO farmer_numbers(number, surveyor_id) VALUES ($1,$2)`, number, sid); err != nil {
			return "", err
		}
		for i, l := range lands {
			if _, err := tx.Exec(ctx, `INSERT INTO lands(id, farmer_id, idx, survey_no, area_ha, register_acres, owner_type, records_consent)
				VALUES (gen_random_uuid(), $1, $2, $3, $4, $5, 'farmer', true)`, id, i, l[0], l[1], l[2]); err != nil {
				return "", err
			}
		}
		return id, nil
	}
	action := func(farmerID, kind, note string) error {
		_, err := tx.Exec(ctx, `INSERT INTO action_items(surveyor_id, farmer_id, kind, note) VALUES ($1,$2,$3,$4)`, sid, farmerID, kind, note)
		return err
	}

	rng := rand.New(rand.NewSource(7))
	allVillages := append(append([]string{}, seedVillages["Kalamnuri"]...), seedVillages["Basmat"]...)
	for i := 0; i < 124; i++ {
		name := firstNames[rng.Intn(len(firstNames))] + " " + lastNames[rng.Intn(len(lastNames))]
		ha := 0.4 + float64(rng.Intn(120))/100
		acres := MaxRegisterAcres(ha)
		if acres < 0.25 {
			acres = 0.25
		}
		mobile := fmt.Sprintf("9%09d", rng.Intn(1e9))
		if _, err := addFarmer(4290+i, name, mobile, allVillages[rng.Intn(len(allVillages))], "submitted",
			[][3]any{{fmt.Sprintf("%d/%d", 10+rng.Intn(400), 1+rng.Intn(4)), ha, acres}}); err != nil {
			return err
		}
	}
	ganesh, err := addFarmer(4414, "Ganesh Vitthal Kale", "9822011223", "Masod", "registered", [][3]any{{"88/1", 0.81, 2.0}})
	if err != nil {
		return err
	}
	adesh, err := addFarmer(4415, "Adesh Ramrao Gulgule", "9822033445", "Belthar", "submitted", [][3]any{{"132", 1.2, 2.75}})
	if err != nil {
		return err
	}
	kamaji, err := addFarmer(4416, "Kamaji Narayan Karhale", "9822055667", "Warud", "submitted", [][3]any{{"45/2", 0.5, 1.0}, {"47", 0.42, 1.0}})
	if err != nil {
		return err
	}
	sunanda, err := addFarmer(4417, "Sunanda Ramrao Raut", "9822041376", "Kurunda", "registered", [][3]any{{"214/2B", 0.61, 1.5}})
	if err != nil {
		return err
	}
	for _, a := range [][3]string{
		{sunanda, "walk_boundary", ""},
		{ganesh, "retake_aadhaar", "sent back"},
		{adesh, "collect_agreement", ""},
		{kamaji, "plantation_proof", ""},
	} {
		if err := action(a[0], a[1], a[2]); err != nil {
			return err
		}
	}
	if _, err := tx.Exec(ctx, `SELECT setval('farmer_number_seq', 4417)`); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
