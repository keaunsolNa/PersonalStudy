# Elasticsearch Repository Function Naming

# **ElasticsearchRepository<T,ID>(org.springframework.data.elasticsearch.repository.ElasticsearchRepository)**

- and
    - • findByNameAndPrice
        
        ```json
        // name과 price 필드에 일치한 결과 조회
        
        {
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must" : [ { -- S : must
        
        ················ "query_string" : {
        
        ···················· "query" : "?",
        
        ···················· "fields" : [ "name" ]
        
        ················ }
        
        ············ },
        
        ············ {
        
        ················ "query_string" : {
        
        ···················· "query" : "?",
        
        ···················· "fields" : [ "price" ]
        
        ················ }
        
        ············ }] -- E : must
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- or
    - • findByNameOrPrice
        
        ```json
        // name또는 price 필드에 일치한 결과 조회
        
        {
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "should" : [ { -- S : should
        
        ················ "query_string" : {
        
        ···················· "query" : "?",
        
        ···················· "fields" : [ "name" ]
        
        ················ }
        
        ············ },
        
        ············ {
        
        ················ "query_string" : {
        
        ···················· "query" : "?",
        
        ···················· "fields" : [ "price" ]
        
        ················ }
        
        ············ }] -- E : should
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- is
    - • findByName
        
        ```json
        // name필드에 일치한 값 조회
        
        {
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must" : [ { -- S : must
        
        ················ "query_string" : {
        
        ···················· "query" : "?",
        
        ···················· "fields" : [ "name" ]
        
        ················ }
        
        ············ }] -- E : must
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- not
    - • findByNameNot
        
        ```json
        // name필드에 포함하지 않는 값 조회
        
        {
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must_not" : [ { -- S : must_not
        
        ················ "query_string" : {
        
        ···················· "query" : "?",
        
        ···················· "fields" : [ "name" ]
        
        ················ }
        
        ············ }] -- E : must_not
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- between
    - • findByPriceBetween
        
        ```json
        // ?0 <= price <= ?1
        
        {
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must" : [ { -- S : must
        
        ················ "range" : {
        
        ···················· "price" : {
        
        ························ "from" : "?",
        
        ························ "to" : "?",
        
        ························ "include_lower" : true,
        
        ························ "include_upper" : true
        
        ···················· }
        
        ················ }
        
        ············ }] -- E : must
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- LessThan
    - • findByPriceLessThan
        
        ```json
        // price < ?0
        
        {
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must" : [ { -- S : must
        
        ················ "range" : {
        
        ···················· "price" : {
        
        ························ "from" : null,
        
        ························ "to" : "?",
        
        ························ "include_lower" : true,
        
        ························ "include_upper" : false
        
        ···················· }
        
        ················ }
        
        ············ }] -- E : must
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- LessThanEqual, before
    - findByPriceLessThanEqual
    - findByPriceBefore
        
        ```json
        // 둘 다 같은 기능
        
        // price <= ?0
        
        {
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must" : [ { -- S : must
        
        ················ "range" : {
        
        ···················· "price" : {
        
        ························ "from" : null,
        
        ························ "to" : "?",
        
        ························ "include_lower" : true,
        
        ························ "include_upper" : true
        
        ···················· }
        
        ················ }
        
        ············ }] -- E : must
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- GreaterThan
    - • findByPriceGreaterThan
        
        ```json
        // price > ?0
        
        {
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must" : [ { -- S : must
        
        ················ "range" : {
        
        ···················· "price" : {
        
        ························ "from" : "?",
        
        ························ "to" : null,
        
        ························ "include_lower" : false,
        
        ························ "include_upper" : true
        
        ···················· }
        
        ················ }
        
        ············ }] -- E : must
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- GreaterThanEqual, after
    - findByPriceGreaterThanEqual
    - findByPriceAfter
        
        ```json
        // 둘 다 같은 기능
        
        // price >= ?0
        
        {
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must" : [ { -- S : must
        
        ················ "range" : {
        
        ···················· "price" : {
        
        ························ "from" : "?",
        
        ························ "to" : null,
        
        ························ "include_lower" : true,
        
        ························ "include_upper" : true
        
        ···················· }
        
        ················ }
        
        ············ }] -- E : must
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- Like, StartingWith
    - findByNameLike
    - findByNameStartingWith
        
        ```json
        // 둘 다 같은 기능
        
        // name필드의 파라미터 값으로 시작하는 데이터 조회
        
        {
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must" : [ { -- S : must
        
        ················ "query_string" : {
        
        ···················· "query" : "?*",
        
        ···················· "fields" : [ "name" ] 
        
        ················ }, 
        
        ················ "analyze_wildcard": true 
        
        ············ }] -- E : must
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- EndingWith
    - findByNameEndingWith
        
        ```json
        // name필드의 파라미터 값으로 끝나는 데이터 조회
        
        {
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must" : [ { -- S : must
        
        ················ "query_string" : {
        
        ···················· "query" : "*?",
        
        ···················· "fields" : [ "name" ] 
        
        ················ }, 
        
        ················ "analyze_wildcard": true 
        
        ············ }] -- E : must
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- Contains/Containing
    - findByNameContaining
    
    ```json
    // name필드의 파라미터 값이 포함되어 있는 데이터 조회
    
    {
    
    ···· "query" : { -- S : query
    
    ········ "bool" : {  -- S : bool
    
    ············ "must" : [ { -- S : must
    
    ················ "query_string" : {
    
    ···················· "query" : "*?*",
    
    ···················· "fields" : [ "name" ] 
    
    ················ }, 
    
    ················ "analyze_wildcard": true 
    
    ············ }] -- E : must
    
    ········ } -- E : bool
    
    ···· } -- E : query
    
    }
    ```
    
- In (when annotated as FieldType.Keyword)
    - • findByNameIn(Collection<String>names)
        
        ```json
        // terms 쿼리를 사용하여 name 필드에서 특정 값 가지는 문서 조회
        
        { 
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must" : [ { -- S : must
        
        ················"bool" : {
        
        ···················· "must" : [ {
        
        ························ "terms" : {
        
        ····························"name" : ["?","?"]
        
        ························ }
        
        ···················· } ]
        
        ················ } 
        
        ············ }] -- E : must
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- In
    - • findByNameIn(Collection<String>names)
        
        ```json
        // name 필드에서 특정 값 가지는 문서 조회
        
        {
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must" : [ { -- S : must
        
        ················ "query_string" : {
        
        ···················· "query" : "\"?\" \"?\""
        
        ···················· "fields" : [ "name" ] 
        
        ················ }
        
        ············ }] -- E : must
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- NotIn (when annotated as FieldType.Keyword)
    - • findByNameNotIn(Collection<String>names)
        
        ```json
        // name필드에 특정 값들이 함께 포함되지 않은 문서 조회
        
        { 
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must" : [ { -- S : must
        
        ················"bool" : {
        
        ···················· "must_not" : [ {
        
        ························ "terms" : {
        
        ····························{"name" : ["?","?"]}
        
        ························ }
        
        ···················· } ]
        
        ················ } 
        
        ············ }] -- E : must
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- NotIn
    - • findByNameNotIn(Collection<String>names)
        
        ```json
        // name필드에 특정 값들이 함께 포함되지 않은 문서 조회
        
        {
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must" : [ { -- S : must
        
        ················ "query_string" : {
        
        ···················· "query" : "NOT(\"?\" \"?\")",
        
        ···················· "fields" : [ "name" ] 
        
        ················ }
        
        ············ }] -- E : must
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- True
    - • findByAvailableTrue
    
    ```json
    // available필드가 true인 문서 조회
    
    {
    
    ···· "query" : { -- S : query
    
    ········ "bool" : {  -- S : bool
    
    ············ "must" : [ { -- S : must
    
    ················ "query_string" : {
    
    ···················· "query" : "true",
    
    ···················· "fields" : [ "available" ] 
    
    ················ }
    
    ············ }] -- E : must
    
    ········ } -- E : bool
    
    ···· } -- E : query
    
    }
    ```
    
- False
    - • findByAvailableFalse
        
        ```json
        // available필드가 false인 문서 조회
        
        {
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must" : [ { -- S : must
        
        ················ "query_string" : {
        
        ···················· "query" : "false",
        
        ···················· "fields" : [ "available" ] 
        
        ················ }
        
        ············ }] -- E : must
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- OrderBy
    - • findByAvailableTrueOrderByNameDesc
        
        ```json
        // available필드가 true인 문서 조회하고, name필드 기준으로 내림차순 정렬
        
        {
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must" : [ { -- S : must
        
        ················ "query_string" : {
        
        ···················· "query" : "true",
        
        ···················· "fields" : [ "available" ] 
        
        ················ },
        
        ················ "sort":[{"name":{"order":"desc"}}]
        
        ············ }] -- E : must
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- Exists, IsNotNull
    - findByNameExists
    - findByNameIsNotNull
        
        ```json
        // name 필드가 존재하는 문서들을 검색
        
        {
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must" : [ { -- S : must
        
        ················ "exists" : {
        
        ···················· "fields" : "name"
        
        ················ }
        
        ············ }] -- E : must
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- IsNull
    - • findByNameIsNull
        
        ```json
        name 필드가 존재하지 않는 문서들을 검색
        
        {
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must_not" : [ { -- S : must_not
        
        ················ "exists" : {
        
        ···················· "fields" : "name"
        
        ················ }
        
        ············ }] -- E : must_not
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- IsEmpty
    - • findByNameIsEmpty
        
        ```json
        // name 필드가 존재하면서 비어있지 않은 문서들을 검색
        
        { 
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must" : [ { -- S : must
        
        ················"bool" : { -- S : bool
        
        ···················· "must" : [ {
        
        ························ "exists" : {
        
        ···························· "field" : "name"
        
        ························ }
        
        ···················· } ],
        
        ···················· "must_not" : [ { -- S : must_not
        
        ························ "wildcard" : {
        
        ···························· "name" : {
        
        ································ "wildcard" : "*"
        
        ···························· }
        
        ························ }
        
        ···················· } ] -- E : must_not
        
        ················ } -- E : bool
        
        ············ }] -- E : must
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```
        
- IsNotEmpty
    - • findByNameIsNotEmpty
        
        ```json
        // name 필드가 어떤 값이든지 상관없는 모든 문서 조회
        
        {
        
        ···· "query" : { -- S : query
        
        ········ "bool" : {  -- S : bool
        
        ············ "must" : [ { -- S : must
        
        ················ "wildcard" : {
        
        ···················· "name" : {
        
        ························ "wildcard" : "*" 
        
        ···················· }
        
        ················ }
        
        ············ }] -- E : must
        
        ········ } -- E : bool
        
        ···· } -- E : query
        
        }
        ```