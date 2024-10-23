[![test](https://github.com/emqx/emqx-snowflake-proxy/actions/workflows/test.yaml/badge.svg?branch=main)](https://github.com/emqx/emqx-snowflake-proxy/actions/workflows/test.yaml)

# prerequisites

1. Have an account on Snowflake
2. Clojure 1.11+
3. Have an user with a role that has the sufficient privileges on all relevant objects.
   - Such role must have:
      - `USAGE` on the database.
      - `CREATE STREAM` and `USAGE` on the schema.
      - `INSERT` on the table(s).
4. Set up a key pair for the user.
   - Create a key pair
     ```sh
     openssl genrsa 2048 | openssl pkcs8 -topk8 -inform PEM -out snowflake_rsa_key.p8 -nocrypt
     openssl rsa -in snowflake_rsa_key.p8 -pubout -out snowflake_rsa_key.pub
     ```
   - Associate the public key with the user in Snowflake.
     ```sql
     alter user testuser set rsa_public_key='MII...';
     desc user testuser;
     ```
5. Configure the service using `config.edn`.  See `config.example.edn` for an example.
6. While it's not required, setting the `APP_NAME` environment variable to an unique and
   fixed string per container/process is recommended to avoid creating unlimited channels
   when the container/process restarts.  Otherwise, an UUID is generated and used.

## running directly

```sh
env APP_NAME=myapp clj -M -m emqx.core
```

## running with repl

```sh
clj
```

```clojure
(require '[emqx.config :as config])
(require '[emqx.channel :as chan])
(let [{:keys [:app-name :client :channels]} (config/get-config!)]
  (chan/start-client client)
  (doseq [chan-params channels]
    (chan/ensure-streaming-agent app-name chan-params)
    (mqtt/start-client chan-params)))
```

## uberjar

```sh
clj -T:build uber

java -jar target/emqx-snowflake-proxy-0.0.0-standalone.jar
```

## docker

```bash
docker build . -t emqx/emqx-snowflake-proxy
docker run -v ./config.edn:/opt/proxy/config.edn ghcr.io/emqx/emqx-snowflake-proxy
```

## testing

```sh
env APP_NAME=myapp clj -X:test

# only tests marked as :integration
clj -X:test :includes '[:integration]'

# except tests marked as :integration
clj -X:test :excludes '[:integration]'

# only a few namespaces
clj -X:test :nses '[emqx.adapter-test]'
```

## formatting

```sh
clj -M:fmt/check
clj -M:fmt/fix
```

Or install [cljfmt](https://github.com/weavejester/cljfmt).

```sh
cljfmt check
cljfmt fix
```
