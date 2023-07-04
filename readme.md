[![test](https://github.com/thalesmg/emqx-snowflake-sidecar/actions/workflows/test.yaml/badge.svg?branch=main)](https://github.com/thalesmg/emqx-snowflake-sidecar/actions/workflows/test.yaml)

## running directly

```sh
clj -M -m emqx.core
```

## running with repl

```sh
clj
```

```clojure
(require '[emqx.http :as server])
(require '[emqx.channel :as chan])
(chan/start-client)
(server/start)
```

## testing

```sh
clj -X:test

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
