# Elasticsearch datatype & reserved word

- Type
    - ⇒ 필드의 데이터 유형 정의
        - FieldType.Text
            - 주로 문장, 문단, 문서 등 긴 텍스트를 처리하는데 사용되는 형식
        - FieldType.Search_As_You_Type
            - 사용자가 입력한 대로 실시간으로 결과를 반환 , 검색어 자동 완성 기능에 사용
        - FieldType.Date
            - 날짜와 시간 정보를 처리하는 날짜 형식의 필드
        - FieldType.Boolean
            - true 또는 false 값을 가지는 필드
        - FieldType.Keyword
            - 문자열이나 숫자 등을 그대로 저장, 분석되지 않는 단순한 문자열로 색인
        - FieldType.Auto
            - FieldType 기본값, 자동 설정
        - FieldType.Binary
            - 파일이나 이미지와 같은 이진 데이터를 저장하는데 사용
        - FieldType.Byte
            - 작은 범위의 8비트 정수 형식으로 저장하는데 사용
        - FieldType.Date_Nanos
            - 날짜, 시간, 밀리초, 마이크로초 및 나노초까지 정밀도를 가지는 날짜와 시간 값을 표현하는데 사용
        - FieldType.Date_Range
            - 특정 날짜부터 종료 날짜까지와 같은 날짜 범위 값을 저장하는데 사용
        - FieldType.Dense_Vector
            - 0이 아닌 모든 값을 가지는 벡터, 두 점 사이의 거리를 계산 할 때 쓰는 유클리디안 거리, 코사인(cos) 유사도 등과 같은 거리 측정에 사용
        - FieldType.Double
            - 64비트 부동 소수점 숫자 형식의 필드
        - FieldType.Double_Range
            - Double타입의 범위 값을 저장하는데 사용
        - FieldType.Flattened
            - 객체형태로 저장하여, 검색 및 정렬 작업을 수행
        - FieldType.Float
            - 32비트 부동 소수점 숫자 형식의 필드
        - FieldType.Float_Range
            - Float타입의 범위 값을 저장하는데 사용
        - FieldType.Half_Float
            - 16비트 부동 소수점 숫자 형식의 필드
        - FieldType.Integer
            - 2³¹ ~ 2³¹-1 범위의 32비트 정수 필드
        - FieldType.Integer_Range
            - Integer타입의 범위 값을 저장하는데 사용
        - FieldType.Ip
            - IPv4 또는 IPv6 주소를 저장하는 필드
        - FieldType.Ip_Range
            - Ip타입의 범위 값을 저장하는데 사용
        - FieldType.Long
            - 2⁶³ ~ 2⁶³-1 범위의 32비트 정수 필드
        - FieldType.Long_Range
            - Long타입의 범위 값을 저장하는데 사용
        - FieldType.Nested
            - 객체형태로 저장하며, 계층구조의 쿼리를 사용할 때 사용
        - FieldType.Object
            - 객체형태로 저장하며, 단순히 그룹화할 때 사용
        - Field FieldType.Nested
            ◦ FieldType.Nested은 Elasticsearch에서 중첩된 문서 구조를 나타내는 데 사용
            ◦ 중첩된 필드가 포함된 객체는 Elasticsearch에 별도의 문서로 저장
            ◦ 중첩된 필드는 계층 구조를 유지하며, 별도의 문서로 색인되고 검색
            ◦ 중첩된 필드는 독립적으로 색인 및 쿼리되므로 별도의 Elasticsearch 문서로 관리
        - Field FieldType.Flattened
            ◦ FieldType.Flattened는 Elasticsearch에서 flattened 한 데이터 구조를 나타내는 데 사용
            ◦ 플랫 필드가 포함된 객체는 부모 문서 내에서 플랫 필드로 저장
            ◦ 객체의 필드는 부모 문서 내에서 개별적인 필드로 "플랫"하게 저장
            ◦ 플랫 필드는 계층 구조를 유지하지 않고 단일 문서 내에서 관리
        - Field FieldType.Object
            ◦ FieldType.Object는 Elasticsearch에서 객체를 나타내는 데 사용
            ◦ 객체의 필드는 Elasticsearch 문서에 포함된 별도의 객체로 저장
            ◦ 객체의 필드는 Elasticsearch 문서의 내부 구조로 유지
            ◦ 객체 필드는 Elasticsearch에서 별도의 문서로 색인 되고 쿼리
        - FieldType.Percolator
            - 쿼리를 저장하며, 저장된 쿼리 결과와 일치하는 document를 찾을 때 사용
        - FieldType.Rank_Feature
            - Dobule형태로 검색 결과의 정확도를 평가하고 순위를 매기는 데 사용
        - FieldType.Rank_Features
            - Map<String, Double> 형태로 다중 랭크 기능을 필요로 할 때 사용
        - FieldType.Scaled_Float
            - 일반적인 부동 소수점보다 작은 범위의 값을 저장, 정밀도 유지하는데 사용
        - FieldType.Short
            - 32,768 ~ 32,767 범위의 16비트 정수 필드
        - FieldType.TokenCount
            - Long 형태로 텍스트 필드의 토큰 수를 계산하고 저장하는데 사용
        - FieldType.Wildcard
            - 특정 패턴을 가지거나 특정 문자열로 끝나는 등 검색을 수행하는데 사용
- Analyzer
    - ⇒ Text 타입의 필드 분석기 정의
        - Korean Analyzer
            - 한국어 텍스트를 처리하기 위한 분석기
        - Nori Analyzer
            - 한국어 텍스트를 처리하기 위한 분석기
            - 언어 모델
                ◦ Nori Analyzer: Nori Analyzer는 Lucene의 형태소 분석기인 Nori를 Elasticsearch에 통합한 것. Nori는 한국어 자연어처리 패키지인 Lucene-Korean-Analyzer를 기반으로 하며, 코모란(Komoran) 형태소 분석기를 사용
                ◦ Korean Analyzer: Korean Analyzer는 Elasticsearch에서 제공하는 한국어 분석기로, NLP(자연어 처리) 라이브러리인 Seunjeon을 기반으로 한다. Seunjeon은 한국어 형태소 분석을 위한 고성능 엔진으로, 다양한 품사 정보와 복합명사 분해 기능을 제공
            - 품사 태깅 ( 형태소에 대해 품사를 파악해 부착(tagging)하는 작업 )
                ◦ Nori Analyzer: Nori Analyzer는 기본적으로 형태소에 품사 태깅을 적용하지 않는다. 단어들은 기본적으로 명사로 처리된다.
                ◦ Korean Analyzer: Korean Analyzer는 Seunjeon 엔진을 통해 형태소 분석을 수행하며, 각 토큰에 품사 태깅을 제공 이를 통해 명사, 동사, 형용사, 부사 등의 다양한 품사 정보를 얻을 수 있다.
            - 사전 및 사용자 사전
                ◦ Nori Analyzer: Nori Analyzer는 사전 기반 형태소 분석기인 코모란(Komoran)을 사용. 이에 따라 사전에 등록된 단어를 바탕으로 형태소 분석을 수행하며, 사용자가 직접 사전을 수정하거나 추가 가능
                ◦ Korean Analyzer: Korean Analyzer는 Seunjeon 엔진을 사용하며, Seunjeon은 내부적으로 사전 및 사전 기반 형태소 분석을 지원. 사용자는 Elasticsearch의 설정 파일에서 사용자 정의 사전을 추가하여 분석 결과를 개선 가능
        - ICU Analysis Plugin
            - 국제화와 다국어 처리를 위한 분석기
        - Elasticsearch Analysis Kuromoji Plugin
            - 일본어 텍스트를 처리하기 위한 분석기
        - Elasticsearch Analysis Smartcn Plugin
            - 중국어 텍스트를 처리하기 위한 분석기
        - Elasticsearch Analysis Stempel Plugin
            - 독일어 텍스트를 처리하기 위한 분석기
        - Elasticsearch Analysis UIMA Plugin
            - Apache UIMA를 사용하여 다양한 언어의 텍스트를 처리하는 분석기
- Coerce
    - ⇒ 데이터 타입 변환
        - true 또는 false 값 지정
        - 기본값 : true
        - false시 자통 타입 변환 비활성화
- Copy_to
    - ⇒ 필드 그룹화
        - 입력한 필드로 그룹화 하여 여러 필드를 단일 필드로 쿼리 가능
            
            ```json
            @Field(type = FieldType.Text, copyTo = ["full_name"])
            val firstName : String,
            @Field(type = FieldType.Text, copyTo = ["full_name"])
            val lastName : String
            
            {
            
            ···· "query" : {
            
            ········ "match" : {
            
            ············ "full_name" : {
            
            ················ "query" : "John Smith",
            
            ················ "operator" : "and"
            
            ············ }
            
            ········ }
            
            ···· }
            
            }
            ```
            
- Doc_values
    - ⇒ 필드의 값에 대한 역색인 구조
        - 집계 및 정렬 작업을 수행하기 위해 필드 값을 메모리에 저장하는데 사용
        - true 또는 false 값 지정
        - 기본값 : true
- Eager_global_ordinals
    - ⇒ 검색 성능 향상
        - 메모리에 미리 로드하여 검색 성능 향상가능
        - 초기 로드 시간 증가, 메모리에 많은 공간 필요
        - true 또는 false 값 지정
        - 기본값 : false
- Enabled
    - ⇒ 인덱스 필드 포함
        - true 또는 false 값 지정
        - 기본값 : true
- Format
    - ⇒ 날짜 및 시간 필드의 저장 및 표현 형식을 지정
        - type이 date인 필드에 "yyyy-MM-dd", "yyyy/MM/dd HH:mm:ss" 같은 형식 지정
- Ignore_above
    - ⇒ 문자열 필드 최대 문자 길이를 제한
        - • 입력한 Int형 정수 크기 만큼 입력 제한
- Ignore_malformed
    - ⇒ 잘못된 형식의 필드 값을 포함한 경우에 대한 처리 방법 설정
        - Int 타입에 dobule 타입이 들어가는 등 잘못된 값이 포함될 경우 처리
        - 기본값 : false
        - true : 잘못된 형식 데이터 무시하고 나머지 필드 처리
        - false : 색인 작업 실패시킴
        - strict : 예외를 발생시켜 색인 작업 완전 중지
- index
    - ⇒ 필드 값 인덱싱 여부 제어
        - true 또는 flase 값 지정
        - 기본값 : true
- index_options
    - ⇒ 역색인에 필드의 어떤 정보를 포함할지 지정
        - docs : 문서의 ID만 저장
        - freqs : 용어가 각 문서에서 얼마나 자주 나타나는지, 빈도 제공
        - positions(기본값) : 용어의 출현 document, 빈도 및 위치 제공, 정교한 검색 지원
        - offsets : 정확한 문서의 매칭을 위해 필요한 용어의 출현 document, 빈도, 위치 및 오프셋 정보 포함
- index_pharses
    - ⇒ 역색인에 용어 구문 정보 포함 여부
        - true 또는 false 값 지정
        - 기본값 : false
        - true : 문장 내에서 용어의 위치와 구문 구조를 포함하여 정확한 구문 검색 가능
        - false : 구문 분석에 관심이 없는 경우 적합
- index_prefixes
    - ⇒ 용어 접두사 색인 생성 및 검색 속도 향상
        - min_chars : 인덱싱할 최소 접두사 길이. 0보다 커야 하며 기본값은 2
        - max_chars : 인덱싱할 최대 접두사 길이. 20보다 작아야 하며 기본값은 5
- meta
    - ⇒ 사용자 정의 정보를 문서에 추가
        - 문서의 추가 정보 (생성 일자, 수정 일자, 작성자 등)를 저장 용도
            
            ```json
            {
            
            ···· "id": "1",
            
            ···· "title": "Sample Document",
            
            ···· "content": "This is a sample document.",
            
            ···· "meta":  {
            
            ········ "created_at": "2023-01-01T10:00:00",
            
            ········  "author": "John Doe"
            
            ···· }
            
            }
            ```
            
- fields
    - ⇒ 동일한 필드를 다른 목적으로 다른 방식으로 색인화
        - 특정 필드의 값을 검색 결과에서 가져와 사용자에게 제공
        - 특정 필드의 값에 대해 집계를 수행
        - 특정 필드의 값을 기준으로 정렬
            - "content" 필드에서 "sample"을 검색하고, 해당 검색 결과에서 "title" 필드의 값을 가져오도록 요청
                
                ```json
                {
                
                ···· "query": {
                
                ········ "match": {
                
                ············ "content": "sample"
                
                ········ }
                
                ···· },
                
                ···· "fields": [ "title"]
                
                }
                ```
                
- normalizer
    - ⇒ 문자열 필드의 값을 정규화
        - • 문자열 필드의 값을 토큰화하지 않고 정규화
            
            ```json
            // "lowercase_normalizer"라는 사용자 정의 normalizer를 정의
            
            // "category" 필드에 이 normalizer를 적용하고, 대소문자를 구분하지 않고 일치 여부를 확인하거나 정렬 가능
            
            PUT /my_index
            
            {
            
            ···· "settings": {
            
            ········  "analysis": {
            
            ············ "normalizer": {
            
            ················ "lowercase_normalizer": {
            
            ···················· "type": "custom",
            
            ···················· "char_filter": [],
            
            ···················· "filter": ["lowercase"]
            
            ················ }
            
            ············ }
            
            ········}
            
            ····},
            
            ···· "mappings": {
            
            ········"properties": {
            
            ············"category": {
            
            ················ "type": "keyword",
            
            ················ "normalizer": "lowercase_normalizer"
            
            ············}
            
            ········}
            
            ····}
            
            }
            ```
            
- norms
    - ⇒ 정규화된 용어 빈도 및 길이 정보 저장
        - true 또는 false 값 지정
        - 기본값 : false
        - 문자열 또는 숫자 필드에서 사용
        - 데이터 용량 증가시키는 요소, 성능 요구 사항에 따라 사용 여부 결정
- null_value
    - ⇒ 필드 값이 null일 때 대체 값 설정
        - null값은 색인하거나 검색이 불가
        - 필드와 동일한 유형으로 값 설정
- position_increment_gap
    - ⇒ 텍스트 필드 공백 추가 설정
        - 기본값 100
        - 값을 너무 작게 설정하면 검색 결과에서 원하는 위치 기반의 정확한 매칭이 어려움
- properties
    - ⇒ 인덱스 매핑 설정
        
        ```json
        {
        
        ···· "mappings": {
        
        ········ "properties": {
        
        ············ "person": {
        
        ················ "type": "nested",
        
        ················ "properties": {
        
        ···················· "name": { "type": "text" },
        
        ···················· "age": { "type": "integer" }
        
        ················ }
        
        ············ }
        
        ········ }
        
        ···· }
        
        }
        ```
        
- search_analyzer
    - ⇒ 검색 시 사용되는 분석기
        - 색인화 과정과는 다른 규칙을 적용하여 검색어를 처리
        
        > @Field(type = FieldType.Text, analyzer = "standard", searchAnalyzer  = "english")
        > 
- similarity
    - ⇒ 검색 시 문서의 유사도 계산 방법 지정
        - 기본값 : BM25(Best Matching 25)
            - 검색어의 텀 빈도, 문서의 텀 빈도, 문서의 길이 등을 고려하여 유사도 계산
- store
    - ⇒ 문서의 필드 저장 방법 설정
        - true 또는 false 값 지정
        - 기본값 : true
        - true : 인덱스에 저장하여 특정 필드 값 직접 조회 가능
        - false : 인덱스에 저장안하고, _source필드를 통해 조회 가능
- subobject
    - ⇒ 중첩된 객체 나타내는 개념
    - mapping 설정에서 중첩해서 설정
        
        ```json
        //books.title, books.author와 같은 하위 필드를 사용하여 중첩된 객체의 속성을 검색
        
        {
        
        ···· "mappings": {
        
        ········ "properties": {
        
        ············ "books": {
        
        ················ "type": "nested",
        
        ················ "properties": {
        
        ···················· "title": {
        
        ························ "type": "text"
        
        ···················· },
        
        ···················· "author": {
        
        ························ "type": "keyword"
        
        ···················· }
        
        ················ }
        
        ············ }
        
        ········ }
        
        ···· }
        
        }
        ```
        
- term_vector
    - ⇒ 문서 내에서 각 용어의 빈도 및 위치 정보를 포함하는 데이터 구조
    - 텍스트 필드에서의 검색과 통계 정보 얻을 수 있음
        - no: 텀 벡터 정보를 저장하지 않는다. (기본값)
        - yes: 텀 벡터 정보를 모든 문서에 저장
        - with_positions: 텀 벡터 정보를 빈도와 함께 저장
        - with_offsets: 텀 벡터 정보를 빈도와 오프셋과 함께 저장
        - with_positions_offsets: 텀 벡터 정보를 빈도, 위치 및 오프셋과 함께 저장
        - with_positions_payloads: 텀 벡터 정보를 빈도, 위치 및 페이로드와 함께 저장
        - with_positions_offsets_payloads: 텀 벡터 정보를 빈도, 위치, 오프셋 및 페이로드와 함께 저장