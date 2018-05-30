Pre-requisite:
---

- Java version 8
- Following ports on localhost are available:
    - 5005 ~ 5009 (java debug)
    - 10002 ~ 10012 (p2p and rpc)
    - 10023 10026 10030 (rpc admin ports)
   

How to run:
---

`$ ./gradlew clean build deployNodes`

`$ build/nodes/runnodes`

Postman:
---

Import `FXForward-Cordapp.postman_collection.json` file under `postman` folder,
then run the query one by one.

__Note:__ to run the `FabBank:SettleFXForward` query, you need the FXForward linearId and Token linearId
