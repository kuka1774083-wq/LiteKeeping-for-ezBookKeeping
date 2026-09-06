# ezBookkeeping API Communication Reference

This document records the endpoints and payloads verified against the `test`
account on 2026-09-06. It intentionally excludes passwords and bearer tokens.

## Base URL and Authentication

Store the server setting as an origin only, for example:

```text
https://888.zr97.top
```

The application appends `/api` and the endpoint path. Do not ask the user to
enter an API path.

### Login

```http
POST {serverOrigin}/api/authorize.json
Content-Type: application/json
Accept: application/json
```

```json
{
  "loginName": "test",
  "password": "<user-entered-password>"
}
```

Successful response shape:

```json
{
  "success": true,
  "result": {
    "token": "<bearer-token>",
    "need2FA": false,
    "user": {
      "username": "test",
      "defaultCurrency": "CNY"
    }
  }
}
```

Persist only `result.token` using Android encrypted storage. Do not persist the
password after authorization. The main app and widget must read the same stored
server origin and token.

All authenticated requests use:

```http
Authorization: Bearer <token>
Accept: application/json
X-Timezone-Offset: 480
X-Timezone-Name: Asia/Shanghai
```

`X-Timezone-Offset` is measured in minutes. Compute it dynamically from the
device timezone rather than hard-coding it in the production app.

## Reference Data Endpoints

| Purpose | Method and path | Response result |
| --- | --- | --- |
| Visible accounts | `GET /api/v1/accounts/list.json?visible_only=true` | Array of accounts |
| Categories | `GET /api/v1/transaction/categories/list.json` | Object keyed by category type |
| Visible tags | `GET /api/v1/transaction/tags/list.json?visible_only=true` | Array of tags |
| Tag groups | `GET /api/v1/transaction/tags/groups/list.json` | Array of custom tag groups |
| Add transaction | `POST /api/v1/transactions/add.json` | Created transaction |
| Read transaction | `GET /api/v1/transactions/get.json?id={id}&with_pictures=false&trim_account=true&trim_category=true&trim_tag=true` | Transaction detail |
| Delete transaction | `POST /api/v1/transactions/delete.json` | `result: true` on success |
| Transaction list | `GET /api/v1/transactions/list.json` | Filtered transaction page |

Account item fields include `id`, `name`, `currency`, `balance`, `category`,
`type`, and `hidden`. Category data is grouped by type: `1` income, `2`
expense, and `3` transfer. Each category may contain `subCategories`.

Tag item fields include `id`, `name`, `groupId`, `displayOrder`, and `hidden`.
Use a string array for multi-select tags.

## Transaction Types

| UI type | `type` | Accounts | Amounts |
| --- | ---: | --- | --- |
| Income | `2` | `sourceAccountId` | `sourceAmount` |
| Expense | `3` | `sourceAccountId` | `sourceAmount` |
| Internal transfer | `4` | `sourceAccountId` and `destinationAccountId`, which must differ | `sourceAmount` and `destinationAmount` |

For income and expense, send `destinationAccountId: "0"` and
`destinationAmount: 0`.

Amounts are integers in the account currency's smallest unit. For CNY, `100`
means CNY 1.00. `time` is a Unix timestamp in seconds. IDs are strings because
they exceed JavaScript's safe integer range.

## Add Transaction Payload

The following internal-transfer request was successfully created, read back,
and deleted from the `test` account.

```json
{
  "type": 4,
  "categoryId": "3841185713743724614",
  "time": 1788693788,
  "utcOffset": 480,
  "sourceAccountId": "3841188550301188096",
  "destinationAccountId": "3841188586808410112",
  "sourceAmount": 100,
  "destinationAmount": 100,
  "hideAmount": false,
  "tagIds": ["3841188638750670848"],
  "pictureIds": [],
  "comment": "widget API probe",
  "clientSessionId": "<new-uuid-per-submit>"
}
```

`pictureIds` remains an empty array because the widget does not support image
upload. When Android location permission is granted, the app sends
`geoLocation: { "latitude": <number>, "longitude": <number> }`; when no
location is available, the field is omitted after the user confirms.
Generate a new UUID for `clientSessionId` on each user submission; it supports
safe retry and duplicate-request handling.

Successful creation returns the created transaction with an `id`,
`timeSequenceId`, all submitted transaction fields, and `editable: true`.
Failure responses contain `success: false`, `errorCode`, `errorMessage`, and
`path`; display `errorMessage` in the widget's failure state.

## Test Account Data

| Item | ID |
| --- | --- |
| Test cash account | `3841188550301188096` |
| Test savings account | `3841188586808410112` |
| Widget test tag | `3841188638750670848` |
| Bank transfer category | `3841185713743724614` |

The verified probe transfer was deleted. Both test accounts remain available
with a CNY 0.00 balance.
