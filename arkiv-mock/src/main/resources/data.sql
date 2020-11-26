DROP TABLE IF EXISTS arkiv;

CREATE TABLE arkiv (
                       id VARCHAR(255) PRIMARY KEY,
                       title VARCHAR(255),
                       tema VARCHAR(255),
                       timesaved TIMESTAMP
);
