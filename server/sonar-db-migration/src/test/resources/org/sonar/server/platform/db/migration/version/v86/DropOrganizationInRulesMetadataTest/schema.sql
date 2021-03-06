CREATE TABLE "RULES_METADATA"(
    "ORGANIZATION_UUID" VARCHAR(40) NOT NULL,
    "NOTE_DATA" CLOB,
    "NOTE_USER_UUID" VARCHAR(255),
    "NOTE_CREATED_AT" BIGINT,
    "NOTE_UPDATED_AT" BIGINT,
    "REMEDIATION_FUNCTION" VARCHAR(20),
    "REMEDIATION_GAP_MULT" VARCHAR(20),
    "REMEDIATION_BASE_EFFORT" VARCHAR(20),
    "TAGS" VARCHAR(4000),
    "AD_HOC_NAME" VARCHAR(200),
    "AD_HOC_DESCRIPTION" CLOB,
    "AD_HOC_SEVERITY" VARCHAR(10),
    "AD_HOC_TYPE" TINYINT,
    "CREATED_AT" BIGINT NOT NULL,
    "UPDATED_AT" BIGINT NOT NULL,
    "RULE_UUID" VARCHAR(40) NOT NULL
);
ALTER TABLE "RULES_METADATA" ADD CONSTRAINT "PK_RULES_METADATA" PRIMARY KEY("RULE_UUID", "ORGANIZATION_UUID");
