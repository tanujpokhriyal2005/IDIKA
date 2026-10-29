-- Which state each village is in, so a state lead sees the farmers of their
-- state whoever onboarded them. Filled at startup (FillStates) from the state
-- boundaries of the imported map data, the address lookup, and the surveyors.
ALTER TABLE villages ADD COLUMN state text;
CREATE INDEX villages_state_idx ON villages (state);
