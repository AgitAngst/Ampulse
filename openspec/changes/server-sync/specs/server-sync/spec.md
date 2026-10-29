## ADDED Requirements

### Requirement: Optional and off by default
The app SHALL NOT use the network for the server mirror until the person signs in on the Server screen, and the Server screen
and the upload SHALL exist only in the `github` build.

#### Scenario: Fresh install
- **WHEN** the app is installed and never signed in
- **THEN** it sends nothing to any server and the Settings row says it is off

#### Scenario: Store build
- **WHEN** the app is built for RuStore or Google Play
- **THEN** the Server row is hidden, no upload runs and the manifest has no network permission

### Requirement: One-way upload
The app SHALL only send data to the signed-in server and SHALL NOT download or apply any data from it.

#### Scenario: Server has other data
- **WHEN** the server holds records the phone does not know
- **THEN** the phone neither reads nor changes its own data because of them

### Requirement: Mirror content
The app SHALL upload profiles, medicines and the doses of the window from 30 days before today to 14 days after it, with
marks, and SHALL NOT upload the medicine cabinet, packs, blood pressure, contacts, the emergency card, injection sites,
amounts or schedule rules.

#### Scenario: Planned dose with a mark
- **WHEN** a daily schedule has a dose at 08:00 that was marked taken at 08:07
- **THEN** the upload has one planned intake with status taken and the mark time as taken time

#### Scenario: Extra dose
- **WHEN** an as-needed dose was logged without a planned time
- **THEN** the upload has an unplanned intake, taken, whose planned time equals its taken time

### Requirement: Stable opaque identifiers
The app SHALL derive every server identifier from SHA-256 over a random phone-local salt, the record kind and its local numbers,
so a repeated upload yields the same identifiers and an identifier does not reveal the local number.

#### Scenario: Repeat upload
- **WHEN** nothing changed since the last upload
- **THEN** the app sends no request

### Requirement: Send only differences
The app SHALL send only new or changed records and delete from the server what it uploaded and what has since disappeared inside
the window, and SHALL update its upload state only for records the server accepted.

#### Scenario: Mark changed
- **WHEN** one dose changes from skipped to taken
- **THEN** exactly that intake is sent

#### Scenario: Rejected record
- **WHEN** the server rejects a record permanently
- **THEN** the app reports it and does not send the same content again until it changes

### Requirement: HTTPS and secrets
The app SHALL refuse a server address that is not HTTPS, SHALL NOT store the password, and SHALL keep the access token only in
private app storage that is not moved to another phone.

#### Scenario: Plain address
- **WHEN** the person enters an address starting with http://
- **THEN** the app refuses and says the connection must be encrypted

### Requirement: Session ended
The app SHALL sign out and erase the token when the server answers 401 to an upload, and SHALL tell the person.

#### Scenario: Token revoked
- **WHEN** an upload gets 401
- **THEN** the Server screen shows that the app signed out and asks to sign in again

### Requirement: Erase on sign-out
The app SHALL offer to sign out and delete from the server everything it uploaded, and SHALL stay signed in if the erase failed.

#### Scenario: No connection while erasing
- **WHEN** the person chooses sign out and erase and the server is unreachable
- **THEN** the copy stays on the server, the person stays signed in and sees the reason
