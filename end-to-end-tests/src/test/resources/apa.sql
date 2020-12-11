DROP TABLE IF EXISTS bepa;
CREATE TABLE bepa
(
id        VARCHAR(255) NOT NULL,
document  BYTEA,
created   TIMESTAMP WITH TIME ZONE NOT NULL default (now() at time zone 'UTC'),
PRIMARY KEY (id)
);

INSERT INTO bepa(id, document, created) VALUES
('0','0','now()'),
('1','0','now()');
