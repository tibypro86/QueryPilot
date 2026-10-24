CREATE TABLE schools (
    school_id INTEGER PRIMARY KEY,
    school_name TEXT,
    district TEXT,
    school_type TEXT
);

CREATE TABLE enrolment (
    school_id INTEGER,
    academic_year INTEGER,
    student_count INTEGER,
    updated_at TEXT
);

CREATE TABLE districts (
    district_id INTEGER PRIMARY KEY,
    district_name TEXT,
    region TEXT
);

INSERT INTO schools (school_id, school_name, district, school_type) VALUES
(1, 'Government Senior Secondary School', 'North District', 'Government'),
(2, 'Central Public School', 'East District', 'Private'),
(3, 'Model Higher Secondary', 'West District', 'Government');

INSERT INTO enrolment (school_id, academic_year, student_count, updated_at) VALUES
(1, 2022, 1240, '2022-09-01'),
(1, 2023, 1098, '2023-09-01'),
(2, 2022, 980, '2022-09-01'),
(2, 2023, 901, '2023-09-01'),
(3, 2022, 870, '2022-09-01'),
(3, 2023, 832, '2023-09-01');

INSERT INTO districts (district_id, district_name, region) VALUES
(1, 'North District', 'North'),
(2, 'East District', 'East'),
(3, 'West District', 'West');
