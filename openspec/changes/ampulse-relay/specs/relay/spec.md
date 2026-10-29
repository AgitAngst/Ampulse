## ADDED Requirements

### Requirement: Opaque storage
The relay SHALL store only ciphertext, random identifiers, sizes and timestamps, and SHALL NOT have access to any key from which a message could be decrypted.

#### Scenario: Backup read
- **WHEN** the relay's database and backup are read in full
- **THEN** no message text, schedule, medicine name or person name can be recovered

### Requirement: Box access by hashed tokens
The relay SHALL accept a write or a read of a box only with a bearer token whose SHA-256 equals the hash registered at the box creation, and SHALL answer an unknown box and a wrong token identically.

#### Scenario: Wrong token
- **WHEN** a request carries a token that does not match the box
- **THEN** the relay answers 404 and stores nothing

### Requirement: Retention and quotas
The relay SHALL delete a message after 7 days, delete a box not used for 90 days, refuse a message larger than 8 KiB and refuse messages beyond 200 or 1 MiB per box.

#### Scenario: Full box
- **WHEN** a box holds 200 messages and another arrives
- **THEN** the relay answers 429 and the oldest messages stay until acknowledged or expired

### Requirement: Acknowledged delete
The relay SHALL delete messages up to a sequence number only when the reader sends the box's read token and that number.

#### Scenario: Acknowledge
- **WHEN** the reader acknowledges up to 5
- **THEN** messages 1 to 5 are removed and a later read after 5 returns only newer messages

### Requirement: Long poll
The relay SHALL hold a read request with `wait` for at most 25 seconds and answer at once when a new message arrives.

#### Scenario: Message during the wait
- **WHEN** a message is posted while a read waits
- **THEN** the read returns it without waiting out the interval

### Requirement: Certificate pin
The relay SHALL serve TLS with a self-signed certificate created at install and SHALL report its SHA-256 fingerprint in `status --json`.

#### Scenario: Status
- **WHEN** `status --json` runs on an installed relay
- **THEN** the object contains `name`, `version`, `running`, `address`, `fingerprint`, `boxes`, `messages` and `disk_free`

### Requirement: Contract commands
The relay binary SHALL provide `install`, `status --json`, `backup` and `uninstall`, and `install` SHALL be safe to repeat, keeping data, certificate and settings.

#### Scenario: Repeated install
- **WHEN** `install` runs on a machine where the relay already works
- **THEN** the binary is replaced, the service restarts and boxes, messages and the certificate are unchanged

### Requirement: Optional feature
The relay SHALL NOT be required for any function of the app, and the documentation SHALL state that the app works fully without it.

#### Scenario: No server configured
- **WHEN** no relay address is set in the app
- **THEN** every function other than relay delivery works as before
