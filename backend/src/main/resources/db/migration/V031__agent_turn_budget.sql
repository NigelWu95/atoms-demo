-- Full-stack generation may need more than 24 tool turns to read and write its files.
-- Only the admissible range changes; existing task budgets and audit remain immutable.
ALTER TABLE agent_tasks DROP CONSTRAINT agent_tasks_max_turns_check;
ALTER TABLE agent_tasks ADD CONSTRAINT agent_tasks_max_turns_check
    CHECK (max_turns BETWEEN 1 AND 128);
