# INC-174: Orders API NullPointerException after deploy

Date: 2025-11-02
Severity: SEV-2
Service: order-service

## Symptoms
About 8% of `POST /api/orders` requests failed with `NullPointerException` at `OrderMapper.toEntity`. Started 3 minutes after a deploy.

## Root cause
A new optional field `couponCode` was dereferenced without a null check. Clients that did not send it caused the exception.

## Fix
Treat the field as optional (`Optional.ofNullable`), add a contract test for requests without the field, and roll back until the fix shipped.
