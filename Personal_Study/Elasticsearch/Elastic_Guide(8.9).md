# Elastic Guide (8.9)

# What is Elasticsearch?

- You know, for search (and analysis)
    - Elasticsearch는 Ealstic Stack의 핵심인 분산 검색 및 분석 엔진이다.
    - Elasticsearch는 Indexing, 검색 및 분석이 일어나는 곳이다.
    - Elasticsearch는 구조화되었거나 구조화되지 않은 텍스트, 숫자 데이터 또는 지리 공간 데이터 등 모든 유형의 데이터에 대해 거의 실시간 검색 및 분석을 제공한다.
    - Elasticsearch는 단순 데이터 검색을 넘어 정보를 집계하여 데이터의 추세와 패턴을 발견할 수 있다.
- Data in: documents and indices
    - Elasticsearch는 분산 문서 저장소이다.
    - Elasticsearch는 JSON 문서로 직렬화된 복잡한 데이터 구조를 저장한다.
    - Cluster에 여러 Elasticsearch 노드가 있는 경우 저장된 문서는 클러스터 전체에 분산 되어 모든 노드에서 즉시 Excess할 수 있다.
    - Elasticsearch는 매우 빠른 전체 텍스트 검색을 지원하는 inverted index 데이터 구조를 사용한다. inverted index는 모든 문서에 나타나는 모든 고유 단어를 나열하고 각 단어가 나오는 모든 문서를 식별한다.
    - Elasticsearch는 모든 필드의 모든 데이터를 Indexing하고 indexing된 필드에는 최적화된 전용 데이터 구조가 있다.
    - Elasticsearch는 문서에서 발생할 수 있는 서로 다른 각 필드를 처리하는 방법을 명시적으로 지정하지 않고도 문서를 indexing할 수 있다.
    - Elasticsearch는 dynamic mapping이 활성화되면 자동으로 새 필드를 감지하고 인덱스에 추가한다.
    - Ealsticsearch는 dynamic mapping을 제어하는 규칙을 사용자가 직접 정의하고 mapping을 명시적으로 정의하여 필드 저장 및 indexing 방법을 완전히 제어할 수 있다.
- Information out: search and analyze
    - Elasticsearch는 Cluster를 관리하고 데이터를 Indexing 및 검색하기 위한 간단하고 일관된 REST API를 제공한다.
        
        ### Searching your data
        
        - Elasticsearch REST API는 구조화된 queries, 전체 텍스트 queries및 이 둘을 결합한 복잡한 queries를 지원한다.
        - 구조화된 queries는 SQL에서 구성할 수 있는 queries유형과 유사하다.
        - 전체 텍스트 queries는 queries 문자열과 일치하는 모든 문서를 찾아 관련성(relevance) 별로 정렬하여 반환한다.
        - Elasticsearch는 고성능 지리 및 수치 queries를 지원하는 최적화된 데이터 구조에서 텍스트가 아닌 데이터를 Indexing한다.
        - Elasticsearch의 Query DSL을 사용하여 이러한 모든 검색 기능에 Access할 수 있다.
        - Elasticsearch는 SQL Style queries를 구성하여 Elasticsearch 내에서 기본적으로 데이터를 검색하고 집계할 수 있으며, JDBC 및 ODBC Driver를 사용하면 광범위한 타사 Application이 SQL을 통해 Elasticsearch와 상호 작용할 수 있다.
        
        ### Analyzing your data
        
        - Elasticsearch aggregations을 사용하면 복잡한 데이터 요약을 작성하고 주요 Metric, Pattern 및 trend에 대한 통찰력을 얻을 수 있다.
        - aggregations은 검색에 사용되는 것과 동일한 데이터 구조를 활용하기에 매우 빠르다. 이를 통해 데이터를 실시간으로 분석하고 시각화할 수 있다.
        - aggregations은 검색 요청과 함께 작동한다. 단일 요청으로 동일한 데이터에 대해 동시에 문서를 검색하고 결과를 필터링하고 분석을 수행할 수 있다.
- Scalability and resilience: clusters, nodes, and shards
    - cluster에 server(node)를 추가하여 용량을 늘리면 Elasticsearch가 자동으로 데이터와 query load를 사용 가능한 모든 노드에 분산 시킨다.
    - Elasticsearch 인덱스는 하나 이상의 물리적 shard를 논리적으로 그룹화한 것이다. 이 때의 각 shard는 실제로 독립적인 인덱스다.
    - 인덱스의 문서를 여러 shard에 분산하고 해당 shard를 여러 노드에 분산함으로써 Elasticsearch는 중복성을 보장할 수 있으며, 이는 노드가 Cluster에 추가될 때 하드웨어 오류로부터 보호하고 query 용량을 증가 시킨다.
    - Elasticsearch는 cluster의 변화에 따라 shard를 자동으로 migration하여 cluster를 재조정한다.
    - shard에는 primary와 replicas의 두 가지 유형이 있으며, 인덱스의 각 문서는 하나의 primary shard에 속한다. replica shard는 primary shard의 복사본이며, 복제본은 데이터의 중복 복사본을 제공하여 하드웨어 오류로부터 보호하고 문서 검색과 같은 읽기 요청을 처리할 수 있는 용량을 늘린다.
    - Index의 primary shard 수는 인덱스가 생성될 때 고정되지만 replica shard 수는 indexing 또는 query 작업을 중단하지 않고 언제든지 변경 가능하다.
        
        ### It depends…
        
        - shard가 많을수록 단순히 해당 인덱스를 유지 관리하는 데 더 많은 overhead가 발생한다.  shard 크기가 클수록 Elasticsearch가 cluster를 재조정해야 할 때 shard를 이동하는 시간이 더 오래 걸린다.
        - 작은 shard를 여러 개 queries하면 shard당 처리 속도가 빨라지지만 query가 많을수록 overhead가 커지므로 적은 수의 큰 shard를 queries하는 것이 더 빠를 수 있다. 즉, 상황에 따라 최선의 shard 크기는 다르다.
            - 보편적으로, 평균 shard 크기를 몇 GB에서 수십 GB 사이로 유지하는 것이 좋다. shard 기반 데이터 사용 사례의 경우 20GB ~ 40GB 범위의 shard를 보는 것이 일반적이다.
        
        ### In case of disaster
        
        - Cluster의 노드는 서로 양호하고 안정적인 연결이 필요하다. 그러나 고가용성을 유지하려면 단일 실패 지점도 피해야 한다. 이를 위한 해답은 클러스터 간 복제(CCR)에 있다.
        - CCR은 기본 cluster에서 hot backup 역할을 할 수 있는 보조 원격 cluster로 인덱스를 자동으로 동기화하는 방법을 제공한다. 기본 cluster가 실패하면 보조 cluster가 대신할 수 있다.
        - cluster간 복제는 능동-수동이다. 기본 cluster의 인덱스는 active leader index이며 모든 쓰기 요청을 처리한다. 보조 cluster에 복제된 인덱스는 read-only followers이다.

# What’s new in 8.9

- Better indexing and search performance under concurrent indexing and search
    - match phrase query나 terms query와 같은 쿼리가 상수 키워드 필드를 대상으로 하는 경우 문서와 일치하지 않도록 쿼리가 다시 작성된 shard에서 쿼리 실행을 건너뛸 수 있다.
    - Elasticsearch는 상수 키워드 필드를 포함한 index mapping을 활용하고 상수 키워드 필드가 index mapping에 정의된 값과 일치하지 않는 경우 문서와 일치하지 않도록 쿼리를 다시 작성하는 방식으로 쿼리를 다시 작성한다.
        - 이로 인해 쿼리가 데이터 노드에서 실행되기 전에 shard 수준 요청이 즉시 반환되어 결과적으로 shard를 완전히 건너 뛴다.
        - Elasticsearch는 불필요한 shard 새로 고침을 피하고 쿼리 대기 시간을 개선하기 위해 가능할 때마다 shard를 건너뛰는 기능을 활용한다. (검색 관련 I/O를 수행하지 않는 방식으로)
        - 쿼리 기준과 일치하지 않는 shard는 검색 유휴 상태로 유지되며 Indexing 처리량은 새로 고침으로 인해 부정적인 영향을 받지 않는다.
- Add multiple queries for ranking to the search endpoint
    - sub_searches라는 새로운 최상위 요소를 추가한다.  sub_searches는 각 하위 검색이 독립적으로 실행되는 순위 지정에 사용되는 검색 목록이다.
    - 여러 쿼리를 실행하기 위해 순위를 사용한 검색을 허용하는 대신 요소 sub_searches가 사용된다. 쿼리와 sub_searches는 함께 사용될 수 없다.
- Make text embedding for kNN search GA
    - 8.9 버전 이후, text_embeddin query_vector_builder kNN 검색에 대한 확장이 일반적으로 사용 가능하다. 해당 기능은 조밀한 vector로 변환하여 의미 체계 검색을 수행하는 데 필요하다.
- Asset tracking -geo_line in time-series aggregations
    - geo_line aggregation에서 track을 빌드 한다.
    - time_series TSDB 기능 및 aggregation의 발전으로 TSID 및 timestamp 순서로 데이터 집게에 의존할 수 있으므로 모든 정렬을 제거할 수 있을 뿐만 아니라 단일 bucket 메모리만 사용할 수 있다.

# Index modules

- Index modules
    - index modules은 인덱스별로 생성되는 module이며 인덱스와 관련된 모든 측면을 제어한다.
        
        ### Index Setting
        
        - index level setting은 인덱스별로 설정할 수 있다.
            - static : 인덱스 생성 시 또는 닫힌 인덱스에서만 설정 가능
            - dynamic : update-index-settins API를 사용하여 Live index에서 변경 가능
            - ⇒ 닫힌 인덱스에서 static 또는 dynamic index settings을 변경하면 index를 삭제하고 다시 만들지 않으면 수정할 수 없는 잘못된 설정이 발생할 수 있다.
            - Static Index Settings
                - 특정 index module과 연결되지 않은 모든 static index settings 목록
                    - index.number_of_shards
                        - 인덱스가 가져야 하는 기본 shard 수. 기본값은 1. 인덱스 생성 시에만 설정 가능. 닫힌 인덱스에서는 변경 불가능
                        - shard 수는 인덱스 당 1024개로 제한.
                    - index.number_of_routing_shards
                        - index.number_of_shards를 primary shard로 routing하는 데 사용되는 정수 값.
                        - Elasticsearch는 인덱스를 분할할 때 이 값을 사용한다.
                        - index.number_of_routing_shards의 기본값은 index의 기본 shard 수에 따라 다르며, 기본값은 최대 1024개의 shard까지 2의 인수로 분할할 수 있도록 설계
                    - index.codec
                        - 저장된 데이터를 LZ4 압축으로 압축하도록 설정하는 값. 저장된 필드 성능이 느려지는 대신 더 높은 압출률을 위해 DEFLATE를 사용하도록 default 설정할 수 있다.
                        - 압축 유형을 업데이트하는 경우 segement가 병합된 후 새 압축 유형이 적용된다. 강제 병합을 사용하여 segment 병합을 강제할 수 있다.
                    - index.routing_partition_size
                        - 사용자 지정 routin값이 이동할 수 있는 shard의 수. default value는 1이며 인덱스 생성 시에만 설정 가능.
                        - index.number_of_shards가 1이 아닌 이상, index.number_of_shards보다 작아야 한다.
                    - index.soft_deltes.enabled
                        - 인덱스에서 일시 삭제 옵션이 활성화되었는지 여부를 나타낸다. 일시 삭제는 인덱스 생성 시와 Elasticsearch 6.5.0 이후에 생성된 인덱스에서만 구성 가능. 기본값은 true
                    - index.soft_deletes.retention_lease.period
                        - 만료된 것으로 간주되기 전에 Shard history retention leases를 유지하는 최대 기간. Shard history retention leases는 lucene 인덱스에서 병합하는 동안 일시 삭제가 유지되도록 한다. 기본값은 12h.
                    - index.load_fixed_bitset_filters_eagerly
                        - 중첩된 쿼리에 대해 cached filters가 미리 로드되는지 여부를 나타낸다. 기본 값은 true
                    - index.shard.check_on_startup
                        - Elasticsearch가 shard를 여는 동안 추가 무결성 검사를 수행할지 여부를 결정한다. 이 검사에서 손상이 감지되면 shard가 열리지 않는다.
                            - false
                                - Shard를 열 때 손상에 대한 추가 검사를 수행하지 않는다. 기본 및 권장 동작
                            - checksum
                                - shard에 있는 모든 파일의 checksum이 내용과 일치하는지 확인.
                            - true
                                - checksum과 동일한 검사를 수행하고 shard의 논리적 불일치도 검사한다.
            - Dynamic Index settins
                - 특정 index module과 연결되지 않은 모든 Dynamic index setting 목록
                    - index.number_of_replicas
                        - 각 primary shard에 있는 replica 수. 기본 값은 1.
                            - ⇒ number_of_replicas가 0인 경우 노드를 다시 시작하는 동안 일시적인 가용성 손실이 발생하거나 데이터 손상 시 영구적인 데이터 손실이 발생할 수 있다.
                    - index.auto_expand_replicas
                        - cluster의 데이터 노드 수에 따라 replica 수를 자동으로 확장.
                    - index.search.idle.after
                        - 검색 유휴 상태로 간주될 때까지 shard가 검색을 수신하거나 요청을 받을 수 없는 기간. 기본 값은 30s
                    - index.refresh_interval
                        - 인덱스에 대한 최근 변경 사항을 검색에 표시하는 새로 고침 작업을 수행하는 빈도. 기본 값은 1s.
                        - 해당 값을 -1로 설정할 경우 새로 고침을 비활성화 할 수 있다.
                        - 해당 설정을 명시적으로 설정되지 않은 경우 최소 몇 초 동안 검색 트래픽이 없는 shard는 index.search.idle.after 검색 요청을 받을 때까지 백 그라운드 새로 고침을 받지 않는다.
                    - index.max_result_window
                        - form + size index에 대한 검색의 최댓값. 기본 값은 10000
                    - index.max_inner_result_window
                        - 이 index에 대한 내부 적중 정의 및 상위 적중 집계에 대한 from + size의 최댓값. 기본 값은 100.
                    - index.max_rescore_window
                        - 인덱스 검색에서 요청에 대한 window_size의 최댓값. 기본 값은 rescore.
                    - index.max_docvalue_fields_search
                        - docvalue_fields 쿼리에서 허용되는 최대 수. 기본값은 100.
                    - index.max_script_fields
                        - script_fields 쿼리에서 허용되는 최대 수. 기본값은 32
                    - index.max_ngram_diff
                        - NGramTokenizer 및 NGramTokenFilter에 대한 min_gram과 max_gram 간의 최대 허용 차이. 기본 값은 1.
                    - index.max_shingle_diff
                        - sgingle 토큰 필터에 대해 max_shingle_size와 min_shingle_size 간에 허용되는 최대 차이. 기본 값은 3
                    - index.max_refresh_listeners
                        - 인덱스의 각 shard에서 사용 가능한 최대 새로 고침 listener 수.
                    - index.analyze.max_token_count
                        - _analyze API를 사용하여 생성할 수 있는 최대 토큰 수. 기본 값은 10000
                    - index.highlight.max_token_count
                        - 하이라이트 요청에 대해 분석할 최대 문자 수. 기본 값은 1000000.
                    - index.max_terms_count
                        - terms 쿼리에서 사용할 수 있는 최대 terms 수. 기본 값은 65536
                    - index.max_regex_length
                        - Regexp Query에서 사용할 수 있는 regex의 최대 길이. 기본 값은 1000
                    - index.query.default_field
                        - 하나 이상의 필드와 일치하는 와일드카드 패턴. 기본 값은 메타데이터 필드를 제외하고 term-level queries에 적합한 모든 필드와 일치하는 값
                    - index.routing.allocation_.enable
                        - 인덱스에 대한 shard 할당 제어.
                            - all (default value) : 모든 shard에 대한 shard 할당 허용
                            - primaries : primary shard에 대해서만 shard 할당 허용
                            - new_primaries : 새로 생성된 primary shard에 대해서만 shard 할당 허용
                            - non : shard 할당 허용하지 않음
                    - index.routing.rebalance.enable
                        - 인덱스에 대한 shard 재조정 활성화.
                            - all : (default value) : 모든 shard에 대해 shard 재조정 허용
                            - primaries : primary shard에 대해서만 shard 재조정 허용
                            - replicas : replicas shard에 대해서만 shard 재조정 허용
                            - none : shard 재조정 허용하지 않음
                    - index.gc_deletes
                        - 삭제된 문서의 버전 번호가 추가 버전 관리 작업에 사용할 수 있는 기간. 기본 값은 60s
                    - index.default_pipeline
                        - 인덱스에 대한 기본 수집 파이프라인. 기본 파이프라인이 설정되어 있으나 파이프라인이 존재하지 않으면 인덱스 요청이 실패함.
                    - index.final_pipeline
                        - 인덱스에 대한 최종 수집 파이프라인. 최종 파이프라인이 설정되고 파이프라인이 존재하지 않으면 indexing 요청 실패.
                    - index.hidden
                        - index를 기본적으로 숨겨야 하는지 여부. 기본 값은 false
- Analysis
    - index analysis module은 문자열 필드를 개별 용어로 변환하는 데 사용할 수 있는 analyzer의 구성 가능한 레지스트리 역할을 한다
        - 문서를 검색 가능하게 만들기 위해 반전된 인덱스에 추가됨
        - 검색어를 생성하는 match 쿼리와 같은 상위 수준 쿼리에서 사용됨.
- Index Shard Allocation
    - 노드에 대한 shard 할당을 제어하기 위한 인덱스 별 설정을 제공하는 module.
    - Index-level shard allocation filtering
        - shard allocation filter를 사용하여 Elasticsearch가 특정 index의 shard를 할당하는 위치를 제어할 수 있다. 이러한 index 별 필터는 cluster 전체 할당 filtering 및 할당 인식과 함께 적용된다.
        - shard allocation filter는 사용자 정의 노드 속성 또는 built-in 속성 (_name, _host_ip, _publish_ip, _ip, _host, _id, _tier, _tier_preference)을 기반으로 할 수 있다.
        - Index lifecycle management는 사용자 지정 노드 특성을 기반으로 하는 filter를 사용하여 단계 간에 이동할 때 shard를 재할당하는 방법을 결정한다.
        - cluster.routing.allocation은 동적이므로 기존 index를 한 노드 집합에서 다른 노드 집합으로 즉시 이동할 수 있다.
            
            ### Enabling index-level shard allocation filtering
            
            1. 각 노드의 구성 파일(elasticsearch.yml) 에서 사용자 정의 노드 속성으로 필터 특성을 지정. 
                
                ```json
                node.attr.size: medium
                
                ./bin/elasticsearch -Enode.attr.size=medium
                ```
                
            2. index에 routing allocation filter 추가. index.routing.allocation settings는 세 가지 유형의 필터를 지원한다. (include, exclude, require). 
                
                ```json
                PUT test/_settings
                {
                	"index.routing.allocation.include.size": "big,medium"
                }
                ```
                
            - 여러 필터를 지정하는 경우 shard를 재배치하려면 노드에서 다음 조건을 동시에 충족해야 한다.
                - require tpye 조건이 지정된 경우 require 모두 충족해야 한다
                - exclude tpye 조건이 지정된 경우 어느 것도 충족되지 않을 수 있다
                - include type 조건이 지정되면 include 조건 중 하나 이상을 충족해야 한다
                    
                    ```json
                    PUT test/_settings
                    {
                      "index.routing.allocation.require.size": "big",
                      "index.routing.allocation.require.rack": "rack1"
                    }
                    ```
                    
            
            ### Index allocation filter settins
            
            - index.routing.allocation.include.{attribute}
                - {attribute}에 쉼표로 구분된 값이 하나 이상 있는 노드에 인덱스를 할당
            - index.routing.allocation.require.{attribute}
                - {attribute}에 쉼표로 구분된 모든 값이 있는 노드에 인덱스를 할당
            - index.routing.allocation.exclude.{attribute}
                - {attribute}에 쉼표로 구분된 값이 없는 노드에 인덱스를 할당
            - Index allocation settins은 다음 기본 제공 속성을 지원한다.
                - _name : 노드 이름으로 노드 일치
                - _host_ip : 호스트 IP 주소(호스트 이름과 연결된 IP)로 노드 일치
                - _publish_ip : 게시 IP 주소로 노드 일치
                - _ip : 일치 _host_ip 또는 _ publish_ip
                - _host : 호스트 이름으로 노드 일치
                - _id : 노드 ID로 노드 일치
                - _tier : 노드의 데이터 계층 역할에 따라 노드 일치
            - 속성 값 지정 시 와일드 카드 사용은 다음과 같이 사용한다.
                
                ```json
                PUT test/_settings
                {
                  "index.routing.allocation.include._ip": "192.168.2.*"
                }
                ```
                
    - Delaying allocation when a node leaves
        
        ### Delaying allocation when a node leaves
        
        - 어떤 이유로든 노드가 cluster를 떠나면 마스터는 다음과 같이 반응한다.
            - replica shard를 primary로 승격하여 기존 primary node 대체
            - 누락된 replica를 대체할 replica shard 할당(충분한 노드가 있을 때)
            - 나머지 노드에서 shard를 고르게 재조정
        - 이러한 작업은 모든 shard가 최대한 빨리, 완전히 복제되도록 하여 데이터 손실로부터 cluster를 보호하기 위한 것이다.
        - 단, node 수준과 cluster 수준 모두에서 동시 복구를 제한하더라도 shard-shuffle은 cluster에 많은 추가 부하를 가할 수 있으며 누락된 노드가 곧 반환될 가능성이 있는 경우에는 필요하지 않을 수 있다.
        - index.unassigned.node_left.delayed_timeout 동적 설정으로 노드가 떠났기에 할당되지 않은 replica shard의 할당을 지연할 수 있다. 기본 값은 1m이며, 인덱스에서 업데이트할 수 있다.
            
            ```json
            PUT _all/_settings
            {
              "settings": {
                "index.unassigned.node_left.delayed_timeout": "5m"
              }
            }
            ```
            
            ### Cancellation of shard relocation
            
            - 지연된 할당 시간이 초과되면 master는 누락된 shard를 복구를 시작할 다른 노드에 할당한다. 누락된 노드가 cluster에 다시 합류하고 해당 shard의 동기화 ID가 기본 노드와 동일한 경우 shard 재배치가 취소되고 대신 동기화된 shard가 복구에 사용된다.
            
            ### Monitoring delayed unassigned shards
            
            - 제한 시간 설정으로 인해 할당이 지연된 shard 수는 cluster health API를 사용하여 볼 수 있다.
                
                ```json
                GET _cluster/health
                ```
                
            
            ### Removing a node permanently
            
            - 노드가 반환되지 않고 Elasticsearch가 누락된 shard를 즉시 할당하도록 하려면 제한 시간을 0으로 업데이트 한다.
                
                ```json
                PUT _all/_settings
                {
                  "settings": {
                    "index.unassigned.node_left.delayed_timeout": "0"
                  }
                }
                ```
                
    - index recovery prioritization
        
        ### index recovery prioritization
        
        - 할당되지 않은 shard는 가능할 때마다 다음의 우선 순위에 따라 복구 된다.
            - 선택적 index.priority 설정 (낮은 것보다 높은 것)
            - 인덱스 생성 날짜(낮은 값보다 높은 값)
            - 인덱스 이름(낮은 것보다 높은 것)
        - index.priority를 통해 인덱스 우선 순위 지정 순서를 사용자 정의하려면 다음과 같은 방식으로 가능하다.
            
            ```json
            PUT index_1
            
            PUT index_2
            
            PUT index_3
            {
              "settings": {
                "index.priority": 10
              }
            }
            
            PUT index_4
            {
              "settings": {
                "index.priority": 5
              }
            }
            ```
            
            - ⇒ 복구 순서는 index 3, 4, 2, 1 순서
        - index.priority 설정은 정수를 허용하며 update index settings API를 통해 라이브 인덱스에서 업데이트 가능하다.
            
            ```json
            PUT index_4/_settings
            {
              "index.priority": 1
            }
            ```
            
    - Total shards per node
        - 아래의 동적 설정을 사용하여 노드당 허용되는 단일 인덱스의 총 shard 수에 대한 엄격한 제한을 지정할 수 있다.
            - index.routing.allocation.total_shards_per_node
                - 단일 노드에 할당될 shard(replica 및 primary)의 최대 수. 기본 값은 무제한
            - cluster.routing.allocation.total_shards_per_node
                - 각 노드에 할당된 primray 및 replica shard의 최대 수. 기본 값은 -1 (무제한)이다.
    - Index-level data tier allocation filtering
        - 인덱스 수준 설정을 사용하여 인덱스가 할당되는 데이터 계층을 _tier_preference 제어 할 수 있다.
        - 아래의 설정은 데이터 노드 역할에 해당한다
            - data_content
            - data_hot
            - data_warm
            - data_cold
            - data_frozen
            
            ### Data tier allocation settings
            
            - index.routing.allocation.include._tier_perference
                - 사용 가능한 노드가 있는 목록의 첫 번째 계층에 인덱스 할당. 이를 통해 기본 계층에서 사용 가능한 노드가 없는 경우 인덱스가 할당되지 않은 상태로 유지되는 것을 방지 가능.
- Index blocks
    - 인덱스 블록은 특정 인덱스에서 사용할 수 있는 작업의 종류를 제한한다. 동적 인덱스 설정을 사용하여 블록을 설정/제거하거나 전용 API를 사용하여 추가할 수 있다.
        
        ### Index block settings
        
        - index.blocks.read_only
            - true로 설정하여 인덱스 및 인덱스 metadata를 읽기 전용으로 만들 수 있다. false는 쓰기 및 metadata 변경 허용.
        - index.blocks.read_only_allow_delete
            - index.blocks.wirte와 유사하지만 더 많은 resources를 사용할 수 있도록 인덱스를 삭제할 수도 있다. disk-based shard allocator는 해당 블록을 자동으로 추가 및 제거할 수 있다.
            - 인덱스 자체를 삭제하는 대신 resources를 해제하기 위해 인덱스에서 문서를 삭제하면 시간이 지남에 따라 인덱스 크기가 증가할 수 있다.
            - 이 때 index.blocks.read_only_allow_delete를 true로 설정하면 문서 삭제가 허용되지 않는다. 그러나 인덱스 자체를 삭제하면 읽기 전용 인덱스 블록이 해제되고 거의 즉시 resource를 사용할 수 있게 된다.
        - index.blocks.read
            - ture로 설정하여 인덱스에 대한 읽기 작업을 비활성화 할 수 있다
        - index.blocks.write
            - true로 설정하여 인덱스에 대한 데이터 쓰기 작업을 비활성화 할 수 있다. read_only와 달리 metadata에는 영향을 주지 않는다.
        - index.blocks.metadata
            - true로 설정하여 인덱스 metadata에 읽기 및 쓰기를 비활성화 할 수 있다.
        
        ### Add index block API
        
        - 인덱스에 인덱스 블록을 추가한다.
            
            ```json
            PUT /my-index-000001/_block/write
            ```
            
            ### Request
            
            - PUT /<index>/_block/<block>
            
            ### Path parameters
            
            - <index>
                - 선택 사항, 문자열
                - 요청을 제한하는 데 사용되는, 쉼표로 구분된 인덱스 이름의 목록 또는 wildcards표현
                - 기본적으로 브록을 추가할 인덱스의 이름을 명시적으로 지정해야 함. all, * 또는 기타 wildcards 표현식을 사용하여 인덱스에 블록을 추가하려면 action.destructive_requires_name 설정을 false로 변경해야 한다.
            - <block>
                - 필수, 문자열
                - 인덱스에 추가할 블록 유형. 유효한 값 목록은 다음과 같다.
                    - metadata
                        - index : 닫기와 같은 metadata 변경 비활성화
                        - read : 읽기 작업 비활성화
                        - read_only : 쓰기 작업 및 metadata 변경을 비활성화
                        - write : 쓰기 작업 비활성화. 단, metadata 변경은 계속 허용
            
            ### Query parameters
            
            - allow_no_indices
                - 선택, boolean
                - flase인 경우 wildcards 표현식, 인덱스 alias 또는 값이 누락되거나 닫힌 인덱스만 대상으로 하는 경우 요청에서 요류를 반환. 기본값은 true
            - expand_wildcards
                - 선택, 문자열
                - wildcards 패턴이 일치시킬 수 있는 인덱스 유형.
                - 쉼표로 구분된 값을 지원하며, 유효한 값은 다음과 같다. (기본 값은 open)
                    - all : 숨겨진 항목을 포함하여 모든 데이터 스트림 또는 인덱스를 일치
                    - open : 열려 있고 숨겨지지 않은 인덱스 탐색. 또한 숨겨지지 않은 모든 데이터 스트림과 일치
                    - closed : 닫혀 있고 숨겨지지 않은 인덱스를 탐색. 또한 숨겨지지 않은 모든 데이터 스트림과 일치. 데이터 스트림을 닫을 수 없음
                    - hidden : 숨겨진 데이터 스트림과 숨겨진 인덱스를 일치. open 또는 closed 둘 다와 결합해 한다
                    - none : wildcards 패턴은 허용되지 않음
                - ignore_unavailable
                    - 선택, boolean
                    - false인 경우 요청이 누락되거나 닫힌 인덱스를 대상으로 하는 경우 오류를 반환. 기본 값은 false
                - master_timeout
                    - 선택, 시간 단위
                    - 마스터 노드와의 연결을 기다리는 시간. 제한시간이 만료되기 전에 응답이 수신되지 않으면 요청이 실패하고 오류가 반환. 기본 값은 30s
                - timeout
                    - 선택, 시간 단위
                    - 응답을 기다리는 시간, 제한 시간이 만료되기 전에 응답이 수신되지 않으면 요청이 실패하고 오류가 반환. 기본 값은 30s
            
            ### Examples
            
            - Request
                
                ```json
                PUT /my-index-000001/_block/write
                ```
                
            - Response
                
                ```json
                {
                  "acknowledged" : true,
                  "shards_acknowledged" : true,
                  "indices" : [ {
                    "name" : "my-index-000001",
                    "blocked" : true
                  } ]
                }
                ```
                
- Mapper
    - Mapper module은 인덱스를 만들 때 또는 Update mapping API를 사용하여 인덱스에 추가된 유형 매핑 정의에 대한 registry 역할을 한다.
    - 미리 정의된 명시적 매핑이 없는 유형에 대한 동적 매핑 지원을 처리한다.
- Merge
    - Elasticsearch의 shard는 Lucene 인덱스이며 Lecene 인덱스는 segement로 나뉜다.
    - segement는 index 데이터가 저장되는 인덱스의 내부 저장 요소이며 변경할 수 없다.
    - 더 작은 segement는 인덱스 크기를 막고 삭제를 영구 삭제 하기 위해 주기적으로 더 큰 segment로 병합된다.
        
        ### Merge scheduling
        
        - 병합 스케줄러(ConcurrentMergeScheduler)는 병합 작업이 필요할 때 실행을 제어한다. 병합은 별도의 Thread에서 실행되며 최대 Thread 수에 도달하면 병합 Thread가 사용 가능해질 때까지 추가 병합이 대기한다.
            - index.merge.scheduler.max_thread_count
                - 한 번에 병합될 수 있는 단일 shard의 최대 thread 수.
- Similarity module
    - Similarity(유사성, 점수 매기기/순위 모델)은 일치하는 문서의 점수를 매기는 방법을 정의한다. similarity 는 필드 당으로 이루어진다. 즉, 매핑을 통해 필드별로 다른 유사성을 정의할 수 있다.
    - Configuring a similarity
        - 대부분의 기존 유사성 또는 사용자 지정 유사성에는 아래와 같이 인덱스 설정을 통해 구성할 수 있는 구성 옵션이 있다. 인덱스 옵션은 인덱스를 생성하거나 인덱스 설정을 업데이트할 때 제공할 수 있다.
            
            ```json
            PUT /index
            {
              "settings": {
                "index": {
                  "similarity": {
                    "my_similarity": {
                      "type": "DFR",
                      "basic_model": "g",
                      "after_effect": "l",
                      "normalization": "h2",
                      "normalization.h2.c": "3.0"
                    }
                  }
                }
              }
            }
            ```
            
        - my_similarity에서 DFR similarity를 구성하여 아래에서 설명하는 것처럼 mapping에 참조할 수 있다.
            
            ```json
            PUT /index/_mapping
            {
              "properties" : {
                "title" : { "type" : "text", "similarity" : "my_similarity" }
              }
            }
            ```
            
    - Available similarities
        - BM25 similarity (default)
            - 기본 제공 tf 정규화가 있고 짧은 필드에 대해 더 잘 작동하도록 되어 있는 TF/IDF 기반 similarity이다.
                
                [https://en.wikipedia.org/wiki/Okapi_BM25](https://en.wikipedia.org/wiki/Okapi_BM25)
                
            - 옵션은 다음과 같다.
                - k1 : 비선형 용어 주파수 정규화(포화)를 제어한다. 기본 값은 1.2
                - b : 문서 길이가 tf 값을 정규화하는 정도를 제어한다. 기본 값은 0.75
                - discount_overlaps : norm을 계산할 때 중복 토큰(위치 중분이 0인 토큰)을 무시할지 여부를 결정. 기본 값은 true
        - DFR similarity
            - 임의성 프레임워크에서 분기를 구현하는 유사성. 옵션은 다음과 같다.
                - basic_model : 가능한 값 (g, if in, ine)
                - after_effect : 가능한 값 (b, 1)
                - normalization : 가능한 값 (no, h1, h2, h3 z)
                - basic_model을 제외한 모든 옵션에는 정규화 값이 필요하다.
        - DFI similarity
            - 독립 모델에서 분기를 구현하는 유사성. 옵션은 다음과 같다.
                - independence_measure : 가능한 값(standardized, saturated, chisquared)
                - DFI similarity를 사용할 때는 좋은 관련성을 얻기 위해 불용어를 제거하지 않는 것이 좋다. 또한 빈도가 예상 빈도보다 낮은 용어는 0점을 얻게 된다.
        - IB similarity
            - 정보 기반 모델.
            - 이 알고리즘은 모든 기호 배포 sequence의 정보 내용이 기본 요소의 반복 사용에 의해 주로 결정된다는 개념을 기반으로 한다. 옵션은 다음과 같다.
                - distribution : 가능한 값(11, spl)
                - lambda : 가능한 값(df, ttf)
                - normalization : 가능한 값 (no, h1, h2, h3 z)
        - LM Dirichlet similarity
            - 옵션은 다음과 같다.
                - mu : 기본 값은 2000
                - 채점 공식은 언어 모델에서 예측한 것보다 발생 횟수가 적은 용어에 음수 점수를 할당한다. 이는 Lucene에서는 허용되지 않으므로 해당 용어는 0점이 된다.
        - LM Jelinek Mercer similarity
            - 알고리즘은 노이즈를 제거하면서 텍스트에서 중요한 패턴을 캡처하려고 시도한다. 옵션은 다음과 같다.
                - lambda
                    - 최적의 값은 Collection과 query에 따라 다르다. 최적의 값은 제목 쿼리의 경우 약 0.1, 긴 쿼리의 경우 0.7이다. 기본 값은 0.1이며 값이 0에 가까워지면 더 많은 쿼리 용어와 일치하는 문서가 더 적은 용어와 일치하는 문서보다 더 높은 순서가 지정된다.
        - Scripted similarity
            - 점수 계산 방법을 지정하기 위해 스크립트를 사용할 수 있는 유사성. 아래는 TF-IDF를 다시 구현하는 방법이다.
                
                ```json
                PUT /index
                {
                  "settings": {
                    "number_of_shards": 1,
                    "similarity": {
                      "scripted_tfidf": {
                        "type": "scripted",
                        "script": {
                          "source": "double tf = Math.sqrt(doc.freq); double idf = Math.log((field.docCount+1.0)/(term.docFreq+1.0)) + 1.0; double norm = 1/Math.sqrt(doc.length); return query.boost * tf * idf * norm;"
                        }
                      }
                    }
                  },
                  "mappings": {
                    "properties": {
                      "field": {
                        "type": "text",
                        "similarity": "scripted_tfidf"
                      }
                    }
                  }
                }
                
                PUT /index/_doc/1
                {
                  "field": "foo bar foo"
                }
                
                PUT /index/_doc/2
                {
                  "field": "bar baz"
                }
                
                POST /index/_refresh
                
                GET /index/_search?explain=true
                {
                  "query": {
                    "query_string": {
                      "query": "foo^1.7",
                      "default_field": "field"
                    }
                  }
                }
                ```
                
            - 결과는 다음과 같다.
                
                ```json
                {
                  "took": 12,
                  "timed_out": false,
                  "_shards": {
                    "total": 1,
                    "successful": 1,
                    "skipped": 0,
                    "failed": 0
                  },
                  "hits": {
                    "total": {
                        "value": 1,
                        "relation": "eq"
                    },
                    "max_score": 1.9508477,
                    "hits": [
                      {
                        "_shard": "[index][0]",
                        "_node": "OzrdjxNtQGaqs4DmioFw9A",
                        "_index": "index",
                        "_id": "1",
                        "_score": 1.9508477,
                        "_source": {
                          "field": "foo bar foo"
                        },
                        "_explanation": {
                          "value": 1.9508477,
                          "description": "weight(field:foo in 0) [PerFieldSimilarity], result of:",
                          "details": [
                            {
                              "value": 1.9508477,
                              "description": "score from ScriptedSimilarity(weightScript=[null], script=[Script{type=inline, lang='painless', idOrCode='double tf = Math.sqrt(doc.freq); double idf = Math.log((field.docCount+1.0)/(term.docFreq+1.0)) + 1.0; double norm = 1/Math.sqrt(doc.length); return query.boost * tf * idf * norm;', options={}, params={}}]) computed from:",
                              "details": [
                                {
                                  "value": 1.0,
                                  "description": "weight",
                                  "details": []
                                },
                                {
                                  "value": 1.7,
                                  "description": "query.boost",
                                  "details": []
                                },
                                {
                                  "value": 2,
                                  "description": "field.docCount",
                                  "details": []
                                },
                                {
                                  "value": 4,
                                  "description": "field.sumDocFreq",
                                  "details": []
                                },
                                {
                                  "value": 5,
                                  "description": "field.sumTotalTermFreq",
                                  "details": []
                                },
                                {
                                  "value": 1,
                                  "description": "term.docFreq",
                                  "details": []
                                },
                                {
                                  "value": 2,
                                  "description": "term.totalTermFreq",
                                  "details": []
                                },
                                {
                                  "value": 2.0,
                                  "description": "doc.freq",
                                  "details": []
                                },
                                {
                                  "value": 3,
                                  "description": "doc.length",
                                  "details": []
                                }
                              ]
                            }
                          ]
                        }
                      }
                    ]
                  }
                }
                ```
                
            - Scripted similarity는 다음의 규칙을 충족하지 않을 경우 Elasticsearch가 자동으로 잘못된 상위 히트를 반환하거나 검색 시 내부 오류로 인해 실패할 수 있다.
                1. 반환된 점수는 양수여야 한다
                2. 다른 모든 변수는 동일하게 유지되며 doc.freq 점수가 증가하더라도 점수가 감소해서는 안 된다. 
                3. 다른 모든 변수는 동일하게 유지되며 doc.length 점수가 증가하더라도 점수가 증가해선 안 된다. 
        - Default similarity
            - Elasticsearch는 default로 구성된 모든 similarity를 사용한다.
            - 인덱스가 생성될 때 인덱스의 모든 필드에 대한 기본 유사성을 변경할 수 있다.
                
                ```json
                PUT /index
                {
                  "settings": {
                    "index": {
                      "similarity": {
                        "default": {
                          "type": "boolean"
                        }
                      }
                    }
                  }
                }
                ```
                
            - 인덱스를 만든 후 기본 유사성을 변경하려면 인덱스를 닫은 후, 아래의 요청을 보내고 나중에 다시 열어야 한다.
                
                ```json
                POST /index/_close
                
                PUT /index/_settings
                {
                  "index": {
                    "similarity": {
                      "default": {
                        "type": "boolean"
                      }
                    }
                  }
                }
                
                POST /index/_open
                ```
                
- Slow Log
    - Search Slow Log
        - shard 수준의 느린 검색 로그를 사용하면 느린 검색(쿼리 및 가져오기 단계)을 전용 로그 파일에 기록할 수 있다.
            
            ```json
            index.search.slowlog.threshold.query.warn: 10s
            index.search.slowlog.threshold.query.info: 5s
            index.search.slowlog.threshold.query.debug: 2s
            index.search.slowlog.threshold.query.trace: 500ms
            
            index.search.slowlog.threshold.fetch.warn: 1s
            index.search.slowlog.threshold.fetch.info: 800ms
            index.search.slowlog.threshold.fetch.debug: 500ms
            index.search.slowlog.threshold.fetch.trace: 200ms
            ```
            
        - 위 설정은 모두 동적이며 update indices settings API을 사용하여 각 인덱스에 대해서도 설정할 수 있다.
            
            ```json
            PUT /my-index-000001/_settings
            {
              "index.search.slowlog.threshold.query.warn": "10s",
              "index.search.slowlog.threshold.query.info": "5s",
              "index.search.slowlog.threshold.query.debug": "2s",
              "index.search.slowlog.threshold.query.trace": "500ms",
              "index.search.slowlog.threshold.fetch.warn": "1s",
              "index.search.slowlog.threshold.fetch.info": "800ms",
              "index.search.slowlog.threshold.fetch.debug": "500ms",
              "index.search.slowlog.threshold.fetch.trace": "200ms"
            }
            ```
            
        - 기본적으로 임계값은 -1로 설정되어 비활성화 되어 있다.
        - 로깅은 특정 shard 내에서 검색 요청 실행을 의미하는 shard 수준 범위에서 수행된다.
        - 실행하기 위해 여러 shard로 broadcast할 수 있는 전체 검색 요청을 포함하지 않는다.
        - Search Slow Log 파일은 log4j2.properties 파일에 구성된다.
    - Identifying search slow log origin
        - 호출이 X-Opaque-ID 헤더로 시작된 경우 사용자 ID는 Search Slow Log에 추가 ID 필드로 포함된다.
            
            ```json
            {
              "type": "index_search_slowlog",
              "timestamp": "2030-08-30T11:59:37,786+02:00",
              "level": "WARN",
              "component": "i.s.s.query",
              "cluster.name": "distribution_run",
              "node.name": "node-0",
              "message": "[index6][0]",
              "took": "78.4micros",
              "took_millis": "0",
              "total_hits": "0 hits",
              "stats": "[]",
              "search_type": "QUERY_THEN_FETCH",
              "total_shards": "1",
              "source": "{\"query\":{\"match_all\":{\"boost\":1.0}}}",
              "id": "MY_USER_ID",
              "cluster.uuid": "Aq-c-PAeQiK3tfBYtig9Bw",
              "node.id": "D7fUYfnfTLa2D7y-xw6tZg"
            }
            ```
            
    - Index Slow log
        - Index Slow log는 Search Slow Log와 기능이 유사하다. 로그 파일 이름은 index_indexing_slowlog.json으로 끝나며 로그 및 임계값은 slowlog와 동일한 방식으로 구성된다.
            
            ```json
            index.indexing.slowlog.threshold.index.warn: 10s
            index.indexing.slowlog.threshold.index.info: 5s
            index.indexing.slowlog.threshold.index.debug: 2s
            index.indexing.slowlog.threshold.index.trace: 500ms
            index.indexing.slowlog.source: 1000
            ```
            
        - 마찬가지로 update indices settins API를 사용하여 동적으로 각 인덱스에 대해 설정할 수 있다.
            
            ```json
            PUT /my-index-000001/_settings
            {
              "index.indexing.slowlog.threshold.index.warn": "10s",
              "index.indexing.slowlog.threshold.index.info": "5s",
              "index.indexing.slowlog.threshold.index.debug": "2s",
              "index.indexing.slowlog.threshold.index.trace": "500ms",
              "index.indexing.slowlog.source": "1000"
            }
            ```
            
        - 기본적으로 Elasticsearch는 slowlog에 _source의 처음 1000자를 기록한다.
        - index.indexing.slowlog.source로 변경할 수 있으며 false 또는 0으로 설정하면 소스 로깅을 건너뛰고, true로 설정하면 크기에 관계 없이 전체 소스를 로깅한다.
        - 원본은 _source 단일 로그 라인에 맞도록 기본적으로 다시 포맷된다.
    - Slow log levels
        - “more verbose” 로그를 끄도록 적절한 임계값을 설정하여 검색 또는 indexing slow log level을 모방할 수 있다. 예를 들어 index.indexing.slowlog.level: INFO를 simulate하려는 경우 index.indexing.slowlog.threshold.index.debug와 index.indexing.slowlog.threshold.index.trace를 -1로 설정하면 된다.
- Store
    - Store
        
        ### File system storage types
        
        - 다양한 파일 시스템 구현 또는 저장 유형이 있지만, Elasticsearch는 기본적으로 운영 환경에 따라 최상의 구현을 선택한다.
        - 파일에서 저장소 유형을 구성하여 모든 인덱스에 대해 저장소 유형을 명시적으로 설정하거나
            
            ```json
            index.store.type: hybridfs
            ```
            
        - 인덱스 생성 시 인덱스별로 설정할 수도 있다.
            
            ```python
            PUT /my-index-000001
            {
              "settings": {
                "index.store.type": "hybridfs"
              }
            }
            ```
            
        - 이는 전문가 전용 설정이며 향후 제거될 수 있다.
        - 지원되는 Storage type
            - fs
                - 기본 파일 시스템 구현. 현재 지원되는 모든 시스템에서 hybridfs이지만 변경될 수 있는 운영 환경에 따라 최상의 구현이 선택된다.
            - simplefs
                - deprecated. 8.0 버전 이후 제거되었다.
                - SimpleFsDirectory 임의 액세스 파일을 사용하여 파일 시스템 스토리지(Lucene에 매핑된)를 간단하게 구현한 것. 이 구현은 동시 성능이 좋지 않으며(여러 Thread가 병목 상태가 됨) 힙 메모리 사용에 대한 일부 최적화를 비활성화한다.
            - niofs
                - NIO FS 유형은 NIO를 사용하여 파일 시스템(Lucene NIOFSDirectory에 매핑된)에 Shard 인덱스를 저장한다.  여러 thread가 동일한 파일에서 동시에 읽을 수 있다. SUN Java 구현의 버그로 인해 Windows에서는 권장되지 않으며 힙 메모리 사용에 대한 일부 최적화를 비활성화한다.
            - mmapfs
                - MMap FS 유형은 파일을 메모리(mmap)에 매핑하여 shard 인덱스를 파일 시스템(Lucene MMapDirectory에 매핑)에 저장한다. 메모리 매핑은 프로세스에서 매핑되는 파일의 크기와 동일한 가상 메모리 주소 공간의 일부를 사용한다.
                - hybridfs
                    - hybridfs 유형은 niofs와 mmapfs의 hybrid로, 읽기 액세스 패턴을 기반으로 각 파일 유형에 가장 적합한 파일 시스템 유형을 선택한다. 현재 Lucene 용어 사전, 규범 및 문서 값 파일만 메모리 매핑된다. 다른 모든 파일은 Lucene NIOFSDirectory을 사용하여 열린다.
    - Preloading data into the file system cache
        - 기본적으로 Elasticsearch는 I/O 작업 캐싱을 위해 운영 체제 파일 시스템 캐시에 전적으로 의존한다.
        - OS에 hot index files의 내용을 메모리에 로드하기 위해 index.store.preload를 설정할 수 있다.
        - 쉼표로 구분된 파일 확장자 목록을 허용한다.
        - 그러나 이러한 방식은 데이터가 물리적 메모리에 로드된 후에만 인덱스를 사용할 수 있으므로 인덱스를 여는 속도가 느려질 수 있다.
        - elasticsearch.yml 파일에서의 정적 설정
            
            ```json
            index.store.preload: ["nvd", "dvd"]
            ```
            
        - 인덱스 생성 시 동적 설정
            
            ```json
            PUT /my-index-000001
            {
              "settings": {
                "index.store.preload": ["nvd", "dvd"]
              }
            }
            ```
            
        - 기본 값은 빈 배열이며 파일 시스템 캐시에 아무 것도 로드되지 않는다.
- Translog
    - Translog
        - Lucene에 대한 변경 사항은 비용이 많이 드는 작업인 Lucene Commit 중에만 디스크에 유지되므로 모든 색인 또는 삭제 작업 후에 수행할 수 없다. 한 commit 이후와 다른 commit 전에 발생하는 변경 사항은 프로세스 종료 또는 하드웨어 오류가 발생할 경우 Lucene에 의해 인덱스에서 제거된다.
        - 각 Shard 복사본은 translog로 알려진 Transaction Log에 작업을 기록한다. 모든 인덱스 및 삭제 작업은 내부 Lucene 인덱스에서 처리된 후 승인되기 전에 translog에 기록된다.
        - 충돌이 발생한 경우 확인되었지만 아직 마지막 Lucene Commit에 포함되지 않은 최근 작업은 shard가 복구될 때 translog에서 대신 복구된다.
        - Elasticsearch flush는 Lucene commit을 수행하고 새로운 translog 생성을 시작하는 프로세스다.
        - flush는 백그라운드에서 자동으로 수행되어 translog가 너무 커지지 않도록 하여 복구 중에 작업을 재생하는 데 상당한 시간이 걸린다.
        - flush를 수동으로 수행하는 기능 역시 API를 통해 가능하다.
    - Translog settins
        - translog의 데이터는 translog가 fsync되고 commit될 때만 디스크에 유지된다.
        - 하드웨어 오류, 운영 체제 충돌, JVM 충돌 또는 shard 오류가 발생하는 경우 이전 translog commit 이후에 기록된 모든 데이터는 손실된다.
        - index.translog.durability는 request로 설정된다.
        - 이는 translog가 기본 및 모든 할당된 복제본에서 성공적으로 fsync 및 commit된 후 Elasticsearch가 client에 대한 인덱스, 삭제, 업데이트 또는 대량 요청의 성공을 보고한다는 의미다.
        - index.translog.durability가 async로 설정된 경우 Elsticsearch는 index.translog.sync_interval 마다 translog를 fsync하고 commit한다. 즉, 충돌 직전에 수행된 모든 작업은 노드가 복구될 때 손실될 수 있다.
        - 인덱스별 설정
            - index.translog.sync_interval
                - 쓰기 작업에 관계없이 translog가 fsync 디스크에 저장되고 commit되는 빈도. 기본 값은 5s, 100ms 보다 작은 값은 허용되지 않음
            - index.translog.durability
                - 모든 인덱스, 삭제, 업데이트 또는 대량 요청 후 translog를 fsync하고 commit할지 여부.
                - 다음은 해당 설정의 매개변수
                    - request
                        - (default) 모든 요청 후 fsync 및 commit. 하드웨어 오류가 발생하면 확인된 모든 쓰기가 이미 디스크에 commit된다.
                    - async
                        - sync_interval마다 백그라운드에서 fsync 및 commit. 오류가 발생하면 마지막 자동 commit 이후 확인된 모든 쓰기가 삭제
            - index.translog.flush_threshold_size
                - translog는 Lucene에서 아직 완전하게 유지되지 않은(Lucene commit 지점의 일부가 아닌) 모든 작업을 저장한다. 이러한 작업은 읽기에 사용될 수 있지만 shard가 중지되어 복구해야 하는 경우 recovery가 필요하다.
                - 이 설정은 복구가 너무 오래 걸리지 않도록 이러한 작업의 최대 총 크기를 제어한다. 최대 크기에 도달하면 flsuh가 발생하여 새로운 Lucene commit 지점을 생성한다. 기본값은 512mb
- History retention
    - History retention
        - Elasticsearch는 replica가 잠시 오프라인 상태인 경우나 cluster간 복제를 하는 경우 shard에서 수행된 일부 작업을 replayed한다.
        - Lucene 수준에서 Elasticsearch가 index에서 수행하는 쓰기 작업은 실제로 두 가지 뿐이며, 이는 새 문서를 indexing 하거나 기존 문서를 삭제하는 것이다.
        - update는 이전 문서를 atomic 하게 삭제한 다음 새 문서를 indexing하여 구현된다.
        - Lucene으로 indexing된 문서에는 해당 indexing 작업을 replayed하는데 필요한 모든 정보가 이미 포함되어 있지만 문서 삭제에는 해당되지 않는다.
        - Elasticsearch는 이를 해결하기 위해  soft deletes라는 기능을 사용하여 Lucene 인덱스에서 최근 삭제를 보존하여 replayed할 수 있도록 한다.
        - 임시 삭제된 문서는 여전히 일부 공간을 차지하기에 Elasticsearch는 인덱스에서 최근에 삭제된 특정 문서만 보존한다.
        - Elasticsearch는 원격 노드에서 shard의 전체 replica를 만드는 것이 항상 가능하기에 shard에서 수행된 모든 작업을 replayed할 필요는 없다.
        - Elasticsearch는 shard history retention leases 메커니즘을 사용하여 향후 replayed해야 할 것으로 예상되는 작업을 추적한다. 작업을 replayed해야 할 수 있는 각 shard replica은 먼저 자체적으로 shard hisotry retention leases를 생성해야 한다.
        - 각 보존 임대는 해당 shard replica이 수신하지 않은 첫 번째 작업의 sequence 번호를 추적한다.
        - 분할된 replica가 새 작업을 수신하면 보존 임대에 포함된 sequence 번호가 증가하여 향후 해당 작업을 replayed할 필요가 없음을 나타낸다.
        - Elasticsearch는 보존 임대에 의해 유지되지 않는 일시 삭제된 작업을 폐기한다.
        - shard 복사가 실패하면 shard history retention leases 업데이트가 중지된다. 즉, Elasticsearch는 실패한 shard 복사가 복구될 때 replayed될 수 있도록 모든 새 작업을 보존한다.
        - 단, 보존 임대는 제한된 기간 동안만 지속된다. shard replica가 충분히 빨리 복구되지 않으면 보존 임대가 만료될 수 있다.
        - 보존 임대 만료 기간은 기본적으로 12h로 되어있으며, 대부분의 합리적인 복구 시나리오에 대해 충분히 길어야 한다.
    - History retention settings
        - index.soft_deletes.enabled
            - 인덱스에서 일시 삭제가 활성화되어있는지 여부를 나타낸다. 일시 삭제는 인덱스 생성 시와 Elasticsearch 6.5.0 이후에 생성된 인덱스에서만 구성할 수 있다. 기본 값은 true
        - index.soft_deletes.retention_lease.period
            - 만료된 것으로 간주되기 전에 shard 기록 보존 임대를 유지하는 최대 기간. 기본 값은 12h
- Index Sorting
    - Index Sorting
        - Index Sorting
            
            ### Index Sorting
            
            - Elasticsearch에서 새 인덱스를 생성할 때 각 shard 내의 segment가 정렬되는 방식을 구성할 수 있다. 기본적으로 Lucene은 정렬을 적용하지 않는다.
            - index.sort.*은 각 segment 내의 문서를 정렬하는 데 사용해야 하는 필드를 정의한다.
                
                <aside>
                💡 중첩 필드는 중첩 문서가 색인 정렬에 의해 손상될 수 있는 인접한 문서 ID에 저장된다는 가정에 의존하기에 색인 정렬과 호환되지 않는다. 중첩 필드가 포함된 인덱스에서 인덱스 정렬이 활성화되면 오류가 발생한다.
                
                </aside>
                
            - single filed에 대한 sort 정의
                
                ```json
                PUT my-index-000001
                {
                  "settings": {
                    "index": {
                      "sort.field": "date", 
                      "sort.order": "desc"  
                    }
                  },
                  "mappings": {
                    "properties": {
                      "date": {
                        "type": "date"
                      }
                    }
                  }
                }
                ```
                
            - 둘 이상의 field를 기준으로 인덱스 정렬
                
                ```json
                PUT my-index-000001
                {
                  "settings": {
                    "index": {
                      "sort.field": [ "username", "date" ], 
                      "sort.order": [ "asc", "desc" ]       
                    }
                  },
                  "mappings": {
                    "properties": {
                      "username": {
                        "type": "keyword",
                        "doc_values": true
                      },
                      "date": {
                        "type": "date"
                      }
                    }
                  }
                }
                ```
                
            - 인덱스 정렬은 다음 설정을 지원한다.
                - index.sort.field
                    - 색인을 정렬하는데 사용되는 필드 목록. doc_values가 있는 boolean, numeric, data, keyword만 허용.
                - index.sort.order
                    - 각 필드에 사용할 정렬 순서. asc와 desc 허용
                - index.sort.mode
                    - 문서를 정렬하기 위해 선택되는 값을 제어한다. min, max를 허용한다.
                - index.sort.missing
                    - 누락된 매개변수는 누락된 문서를 처리하는 방법을 지정한다. _last(필드에 값이 없는 문서가 마지막으로 정렬), _first(해당 필드에 대한 값이 없는 문서가 먼저 정렬) 허용
            - 인덱스 정렬은 인덱스 생성 시 한 번만 정의할 수 있다. 기존 인덱스에 대한 정렬을 추가하거나 업데이트할 수 없다.
        - Early termination of search request
            
            ### Early termination of search request
            
            - 기본적으로 Elasticsearch에서 검색 요청은 지정된 정렬로 정렬된 상위 문서를 검색하기 위해 쿼리와 일치하는 모든 문서를 방문해야 한다.
            - 색인 정렬과 검색 정렬이 동일할 때 segment당 방문해야 하는 문서 수를 제한하여 전체적으로 N개의 최상위 문서를 검색할 수 있다.
            - 다음과 같이 타임스탬프 필드별로 정렬된 이벤트가 포함된 인덱스가 있을 때
                
                ```json
                PUT events
                {
                  "settings": {
                    "index": {
                      "sort.field": "timestamp",
                      "sort.order": "desc" 
                    }
                  },
                  "mappings": {
                    "properties": {
                      "timestamp": {
                        "type": "date"
                      }
                    }
                  }
                }
                ```
                
            - 아래와 같은 방식으로 최근 10개의 이벤트를 검색할 수 있다.
                
                ```json
                GET /events/_search
                {
                  "size": 10,
                  "sort": [
                    { "timestamp": "desc" }
                  ]
                }
                ```
                
            - Elasticsearch는 각 segment의 상위 문서가 인덱스에 이미 정렬되어 있음을 감지하고 segment당 처음 N개의 문서만 비교한다. 쿼리와 일치하는 나머지 문서는 총 결과 수를 계산하고 집계를 구축하기 위해 수집된다.
            - 단, 최근 10개의 이벤트 외의 총 문서 수에는 관심이 없을 경우에는 다음과 같이 요청을 보낼 수 있다.
                
                ```json
                GET /events/_search
                {
                  "size": 10,
                  "sort": [ 
                      { "timestamp": "desc" }
                  ],
                  "track_total_hits": false
                }
                ```
                
            - 이러한 요청의 경우 Elasticsearch는 문서 수를 세지 않고 segment당 N개의 문서가 수집되는 즉시 쿼리를 종료할 수 있다.
                
                ```json
                {
                  "_shards": ...
                   "hits" : {  
                      "max_score" : null,
                      "hits" : []
                  },
                  "took": 20,
                  "timed_out": false
                }
                ```
                
    - Use Index sorting to speed up conjunctions
        
        ### Use Index sorting to speed up conjunctions
        
        - 색인 정렬은 접속사(a AND b AND …)를 보다 효율적으로 만드는 방식으로 Lucene 문서 ID(_id와 결합되지 않음)를 구성하는 데 유용할 수 있다. 효율성을 위해 접속사는 어떤 절이 일치하지 않으면 전체 접속사가 일치하지 않는다는 사실에 의존한다.
        - 색인 정렬을 사용하면 일치하지 않는 문서를 함께 넣을 수 있으므로 연결과 일치하지 않는 광범위한 문서 ID를 효율적으로 건너뛸 수 있다.
        - 이는 Cardinarlity가 낮은 필드에서만 작동한다. 일반적으로 Cardinarlity가 낮고 필터링에 자주 사용되는 필드를 먼저 정렬해야 한다.
        - 정렬 순서는 중요하지 않은데, 이는 동일한 절과 일치하는 값을 서로 가깝게 배치하는 것에만 관심이 있기 때문이다.
- Indexing pressure
    
    ### Indexing pressure
    
    - Indexing pressure는 Indexing 요청과 같은 외부 작업 또는 복구 cluster 간 복제와 같은 내부 메커니즘을 통해 쌓일 수 있다. 너무 많은 Indexing 작업이 시스템에 도입되면 cluster가 포화 상태가 될 수 있는 것. 이는 검색, cluster 조정 및 background 처리와 같은 다른 작업에 부정적인 영향을 미칠 수 있다.
    - 이러한 문제를 방지하기 위해 Elasticsearch는 indexing 부하를 내부적으로 모니터링한다. 부하가 일정 한도를 초과하면 새로운 indexing 작업이 거부된다.
        
        ### Indexing stages
        
        - 외부 indexing 작업은 조정, 기본 및 복제의 세 단계를 거친다. [기본 쓰기 모델](https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-replication.html#basic-write-model) 참조
        
        ### Memory Limits
        
        - indexing_pressure.memory.limit 노드 설정은 미해결 indexing 요청에 사용할 수 있는 바이트 수를 제한한다. 이 설정은 기본적으로 Heap의 10%다.
        - upstrea 단계는 모든 downstream 단계가 완료될 때까지 overhead된 요청을 처리한다. 예를 들어 조정 요청은 primary와 replica 단계가 완료될 때까지 고려된 상태로 유지된다. primary 요청은 동기화된 각 replica이 필요한 경우 replica retries를 활성화하도록 응답할 때까지 고려된 상태로 유지된다.
        - 미해결 조정, primary 및 replica indexing 바이트 수가 구성된 제한을 초과하면 노드는 조정 또는 기본 단계에서 새 indexing 작업을 거부하기 시작한다.
        - 미해결 replica indxing 바이트 수가 구성된 제한의 1.5배를 초과하면 노드는 복제 단계에서 새 indexing 작업을 거부하기 시작한다.
        - indexing_pressure.memory.limit 설정은 기본 10%로 되어있으며, 이는 넉넉한 크기다. Indexing 요청만 이 한도에 영향을 미치며 이는 Heap 공간을 필요로 하는 추가 Indexing overhead(buffer, listner 등)가 있음을 의미한다. 이 제한을 너무 높게 설정하면 다른 작업 및 구성 요소에 대해 작동 메모리를 거부할 수 있다.
        
        ### Monitoring
        
        - [node stats API](https://www.elastic.co/guide/en/elasticsearch/reference/current/cluster-nodes-stats.html#cluster-nodes-stats-api-response-body-indexing-pressure)를 사용하여 indexing pressure 지표를 검색할 수 있다.
        
        ### Indexing pressure settings
        
        - indexing_pressure.memory.limit은 인덱싱 요청에서 사용할 수 있는 미해결 바이트 수다. 제한에 도달하거나 초과하면 노드는 새로운 조정 및 기본 작업을 거부한다. replica 작업이 이 제한의 1.5배를 사용하면 노드는 새 replica 작업을 거부한다. 기본 값은 heap의 10%

# Mapping

- Mapping
    
    ### Mapping
    
    - Mapping은 문서와 문서에 포함된 필드가 저장되고 Indexing되는 방식을 정의하는 프로세스다.
    - 각 document는 각각 고유한 데이터 유형이 있는 필드 모음이며, 데이터를 mapping할 때 document와 관련된 필드 목록이 포함된 mapping 정의를 생성한다.
    - mapping definition에는 문서의 연결된 metadata가 처리되는 방식을 사용자 지정하는 _source 필드와 같은 metadata filed도 포함된다.
        
        <aside>
        💡 7.0.0 버전 이전에는 mapping definition에 유형 이름이 포함되었다. 7.0.0 이상에서는 더 이상 default mapping을 허용하지 않는다.
        
        </aside>
        
        ### Dynamic mapping
        
        - Dynamic mapping을 사용하면 막 시작했을 때 데이터를 실험하고 탐색할 수 있다. Elasticsearch는 문서를 indexing하는 것만으로 새 필드를 자동으로 추가한다. 최상위 mapping과 내부 object 및 nested 필드에 필드를 추가할 수 있다.
        - Dynamic templates를 사용하여 일치 조건에 따라 동적으로 추가된 필드에 적용되는 사용자 지정 mapping을 정의한다.
        
        ### Explicit mapping
        
        - Explicit mapping(명시적 매핑)을 사용 하면 mapping definition을 정의하는 방법을 정확하게 선택할 수 있다.
        - reindexing 없이 schema를 변경하려면 runtime field를 사용할 수 있다. indexing된 필드와 함께 runtime filed를 사용하여 resource 사용량과 성능의 균형을 맞출 수 있다. 인덱스는 더 작지만 검색 성능은 더 느리다.
        
        ### Settings to prevent mapping explosion
        
        - 인덱스에 너무 많은 필드를 정의하면 mapping explosion으로 이어질 수 있다.
        - mapping limit settings을 사용하여 filed mapping(수동 또는 동적으로 생성된)의 수를 제한하고 문서로 인해 mapping explosion이 발생하지 않도록 해야 한다.
- Dynamic mapping
    
    ### Dynamic mapping
    
    - Elasticsearch의 가장 중요한 기능 중 하나는 방해가 되지 않도록 최대한 빨리 데이터 탐색을 시작할 수 있도록 한다는 것이다. document를 indexing하면 index, 유형 및 필드가 자동으로 표시된다.
        
        ```json
        PUT data/_doc/
        { "count": 5 }
        ```
        
    - 이러한 새 필드의 자동 감지 및 추가를 Dynamic mapping이라고 한다. dynamic mapping 규칙은 Dynamic field mappings와 Dynamic templates를 사용하여 용도에 맞게 사용자 지정할 수 있다.
        - Dynamic field mappings
            - ⇒ 동적 필드 감지를 관리하는 규칙
        - Dynamic templates
            - ⇒ 동적으로 추가된 필드에 대한 매핑을 구성하는 사용자 지정 규칙
    - Dynamic field mapping
        
        ### Dynamic field mapping
        
        - Elasticsearch가 docuemnt에서 새 필드를 감지하면 기본적으로 type mapping에 field를 동적으로 추가한다. dynamic 매개변수가 이 동작을 제어한다.
        - dynamic 매개변수를 true 또는 runtime으로 설정하여 수신 문서를 기반으로 필드를 동적으로 생성하도록 Elasticsearch에 명시적으로 지시할 수 있다. Dynamic mapping이 활성화되면 Elasticsearch는 다음 표의 규칙을 사용하여 각 필드의 데이터 유형을 mapping하는 방법을 결정한다.
            
            
            | JSON data type | Elasticsearch data type | Elasticsearch data type |
            | --- | --- | --- |
            |  | "dynamic”:”true” | "dynamic”:”runtime” |
            | null | No field added | No field added |
            | true or false | boolean | boolean |
            | double | float | double |
            | long | long | long |
            | object | object | No field added |
            | array | Depends on the first
            non-null value in the array | Depends on the first
            non-null value in the array |
            | string that passes date detection | date | date |
            | string taht passes numeric detection | float or long | double or long |
            | string taht doens’t pass date
            detection or numeric detection | text with a .keyword sub-field | keyword |
        - 문서와 object 수준 모두에서 dynamic mapping을 비활성화 할 수 있다. dynamic 매개변수를 false로 설정하면 새 필드가 무시되고 strict는 Elasticsearch에서 알 수 없는 필드를 발견하면 문서를 거부한다.
        - 날짜 감지 및 숫자 감지에 대한 dynamic filed mapping 규칙을 사용자 정의할 수 있다. 추가 dynamic field에 적용할 수 있는 사용자 지정 mapping 규칙을 정의하려면 dynamic_templates를 사용한다.
            
            ### Date detection
            
            - date_detction이 활성화 된 경우(default), 새 문자열 필드를 검사하여 내용이 dynamic_date_formats에 지정된 날짜 패턴과 일치하는지 확인한다. 일치하는 항목이 있으면 date 형식으로 새 필드가 추가된다.
            - dynamic_date_formats의 기본값은 다음과 같다.
                - [”strict_date_optional_time”, “yyyy/MM/dd HH:mm:ss Z||yyyy/MM/dd Z”]
                    
                    ```json
                    PUT my-index-000001/_doc/1
                    {
                      "create_date": "2015/09/02"
                    }
                    
                    GET my-index-000001/_mapping
                    ```
                    
            
            ### Disabling date detection
            
            - date_detection을 false로 설정하여 동적 날짜 감지를 비활성화할 수 있다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "date_detection": false
                  }
                }
                
                PUT my-index-000001/_doc/1 
                {
                  "create_date": "2015/09/02"
                }
                ```
                
            
            ### Customizing detected date formats
            
            - 또는, dynamic_date_formats을 통해 custom 날짜 형식을 지원하도록 사용자 정의할 수 있다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "dynamic_date_formats": ["MM/dd/yyyy"]
                  }
                }
                
                PUT my-index-000001/_doc/1
                {
                  "create_date": "09/25/2015"
                }
                ```
                
                - 이 때 날짜 패턴의 배열을 구성하는 것과 || 로 구분된 단일 문자열에 여러 패턴을 구성하는 것에는 차이가 있다. 날짜 패턴 배열을 구성할 때 매핑되지 않은 날짜 필드가 있는 첫 번째 문서의 날짜와 일치하는 패턴이 해당 필드의 매핑을 결정한다.
                - ,로 구분한 경우
                    
                    ```json
                    PUT my-index-000001
                    {
                      "mappings": {
                        "dynamic_date_formats": [ "yyyy/MM", "MM/dd/yyyy"]
                      }
                    }
                    
                    PUT my-index-000001/_doc/1
                    {
                      "create_date": "09/25/2015"
                    }
                    ```
                    
                    - 결과 값
                        
                        ```json
                        {
                          "my-index-000001": {
                            "mappings": {
                              "dynamic_date_formats": [
                                "yyyy/MM",
                                "MM/dd/yyyy"
                              ],
                              "properties": {
                                "create_date": {
                                  "type": "date",
                                  "format": "MM/dd/yyyy"
                                }
                              }
                            }
                          }
                        }
                        ```
                        
                - || 을 사용한 경우
                    
                    ```json
                    PUT my-index-000001
                    {
                      "mappings": {
                        "dynamic_date_formats": [ "yyyy/MM||MM/dd/yyyy"]
                      }
                    }
                    
                    PUT my-index-000001/_doc/1
                    {
                      "create_date": "09/25/2015"
                    }
                    ```
                    
                    - 결과 값
                        
                        ```json
                        {
                          "my-index-000001": {
                            "mappings": {
                              "dynamic_date_formats": [
                                "yyyy/MM||MM/dd/yyyy"
                              ],
                              "properties": {
                                "create_date": {
                                  "type": "date",
                                  "format": "yyyy/MM||MM/dd/yyyy"
                                }
                              }
                            }
                          }
                        }
                        ```
                        
            
            ### Numeric detection
            
            - JSON은 기본 부동 소수점 및 정수 데이터 유형을 지원하지만 일부 애플리케이션 또는 언어는 때때로 숫자를 문자열로 rendering할 수 있다. 일반적으로 올바른 솔루션은 이러한 필드를 명시적으로 mapping하는 것이지만, 숫자 감지(default는 비활성화)를 활성화하여 자동 수행이 가능하다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "numeric_detection": true
                  }
                }
                
                PUT my-index-000001/_doc/1
                {
                  "my_float":   "1.0", 
                  "my_integer": "1" 
                }
                ```
                
    - Dynamic templates
        - Dynamic templates
            
            ### Dynamic templates
            
            - Dynamic templates를 사용하면 Elasticsearch가 기본 Dynamic filed mapping 규칙을 넘어 데이터를 mapping하는 방법을 더 잘 제어할 수 있다.
            - 동적 매개변수를 true 또는 runtime으로 설정하여 dynamic mapping을 활성화한다.
            - 그 뒤 Dynamic templates를 사용하여 일치 조건에 따라 동적으로 추가된 필드에 적용할 수 있는 사용자 지정 mapping을 정의한다.
            - Dynamic mapping은 명명된 개체의 배열로 지정된다.
                
                ```json
                "dynamic_templates": [
                    {
                      "my_template_name": { 
                        ... match conditions ... 
                        "mapping": { ... } 
                      }
                    },
                    ...
                  ]
                ```
                
        - Validating dynamic templates
            
            ### Validating dynamic templates
            
            - 제공된 mapping에 잘못된 mapping snippet이 포함된 경우 유효성 검사 오류가 반환된다.
            - 유효성 검사는 index 시간에 dynamic templates를 적용할 때 발생하며 대부분의 경우 dynamic templates가 업데이트 될 때 발생한다.
            - 잘못된 mapping snippet을 제공하면 특정 조건에서 dynamic templates의 업데이트 또는 유효성 검사가 실패할 수 있다.
                - match_mapping_type에 no가 지정되었지만 templates가 하나 이상의 사전 정의된 mapping 유형에 유효하면 mapping snippet이 유효한 것으로 간주된다.
                - 그러나 templates과 일치하는 필드가 다른 유형으로 indexing되는 경우 indexing 시 유효성 검사 오류가 반환된다.
                - {name} placeholder가 mapping snippet에서 사용되는 경우 dynamic templates를 업데이트할 때 유효성 검사를 건너뛴다. 이후 index 시간에 templates이 적용될 때 유효성 검사가 발생한다.
            - templates는 순서대로 처리되며 첫 번째로 일치하는 templates이 우선한다. update mapping API를 통해 새 dynamic templates을 넣을 때 기존 templates를 모두 덮어쓴다.
        - Mapping runtime fields in a dynamic template
            
            ### Mapping runtime fields in a dynamic template
            
            - Elasticsearch가 특정 유형의 새 필드를 런타임 필드로 dynamic mapping 하려면 “dynamic”:”runtime” index mapping에서 설정한다. 이러한 필드는 indexing되지 않으며 _source 쿼리 시 로드된다.
            - 또는 기본 dynamic mapping 규칙을 사용한 다음 dynamic templates을 생성하여 특정 필드를 runtime filed로 mapping할 수 있다. 인덱스 mapping을 “dynamic”:”true”로 설정한 다음 dynamic templates을 생성하여 특정 유형의 새 필드를 runtime field로 mapping한다.
                
                ```json
                PUT my-index-000001/
                {
                  "mappings": {
                    "dynamic_templates": [
                      {
                        "strings_as_ip": {
                          "match_mapping_type": "string",
                          "match": "ip*",
                          "runtime": {
                            "type": "ip"
                          }
                        }
                      }
                    ]
                  }
                }
                ```
                
                ### match_mapping_type
                
                - match_mapping_type은 JSON Parser가 감지한 데이터 유형이다. Elasticsearch는 다음 데이터 유형을 자동으로 감지한다.
                    
                    
                    | JSON data type | Elasticsearch data type | Elasticsearch data type |
                    | --- | --- | --- |
                    |  | "dynamic”:”true” | "dynamic”:”runtime” |
                    | null | No field added | No field added |
                    | true or false | boolean | boolean |
                    | double | float | double |
                    | long | long | long |
                    | object | object | No field added |
                    | array | Depends on the first
                    non-null value in the array | Depends on the first
                    non-null value in the array |
                    | string that passes date detection | date | date |
                    | string taht passes numeric detection | float or long | double or long |
                    | string taht doens’t pass date
                    detection or numeric detection | text with a .keyword sub-field | keyword |
                - 모든 데이터 유형을 일치시키려면 와일드카드(*)를 사용한다.
                - 예를 들어 모든 Integer 정수 필드를 long으로 mapping하고, 모든 String 필드를 text나 keyword로 매핑하려면 다음 template을 사용할 수 있다.
                    
                    ```json
                    PUT my-index-000001
                    {
                      "mappings": {
                        "dynamic_templates": [
                          {
                            "integers": {
                              "match_mapping_type": "long",
                              "mapping": {
                                "type": "integer"
                              }
                            }
                          },
                          {
                            "strings": {
                              "match_mapping_type": "string",
                              "mapping": {
                                "type": "text",
                                "fields": {
                                  "raw": {
                                    "type":  "keyword",
                                    "ignore_above": 256
                                  }
                                }
                              }
                            }
                          }
                        ]
                      }
                    }
                    
                    PUT my-index-000001/_doc/1
                    {
                      "my_integer": 5, 
                      "my_string": "Some string" 
                    }
                    ```
                    
                
                ### match and unmatch
                
                - match 매개변수는 하나 이상의 패턴을 사용하여 필드 이름을 일치시키는 반면, unmatch는 하나 이상의 패턴을 사용하여 match로 매칭된 필드를 제외한다.
                - match_pattern 매개변수는 match 매개변수의 동작을 조정하여 단순한 와일드카드 대신 필드 이름에 일치하는 전체 Java 정규식을 지원한다.
                    
                    ```json
                    PUT my-index-000001
                    {
                      "mappings": {
                        "dynamic_templates": [
                          {
                            "longs_as_strings": {
                              "match_mapping_type": "string",
                              "match":   "long_*",
                              "unmatch": "*_text",
                              "mapping": {
                                "type": "long"
                              }
                            }
                          }
                        ]
                      }
                    }
                    
                    PUT my-index-000001/_doc/1
                    {
                      "long_num": "5", 
                      "long_text": "foo" 
                    }
                    ```
                    
                - match나 unmatch는 필드에 JSON 배열을 사용하여 패턴 목록을 지정할 수 있다.
                    
                    ```json
                    PUT my-index-000001
                    {
                      "mappings": {
                        "dynamic_templates": [
                          {
                            "ip_fields": {
                              "match":   ["ip_*", "*_ip"],
                              "unmatch": ["one*", "*two"],
                              "mapping": {
                                "type": "ip"
                              }
                            }
                          }
                        ]
                      }
                    }
                    
                    PUT my-index/_doc/1
                    {
                      "one_ip":   "will not match", 
                      "ip_two":   "will not match", 
                      "three_ip": "12.12.12.12", 
                      "ip_four":  "13.13.13.13" 
                    }
                    ```
                    
                
                ### path_match and path_unmatch
                
                - path_match, path_unmatch 파라미터는 match, unmatch와 같은 방식으로 작동하지만 최종 이름뿐만 아니라 필드에 대한 전체 점선 경로에서 작동한다.
                - 아래의 예는 중간 필드를 제외하고 이름 개체의 모든 필드 값을 최상위 full_name 필드에 복사한다.
                    
                    ```json
                    PUT my-index-000001
                    {
                      "mappings": {
                        "dynamic_templates": [
                          {
                            "full_name": {
                              "path_match":   "name.*",
                              "path_unmatch": "*.middle",
                              "mapping": {
                                "type":       "text",
                                "copy_to":    "full_name"
                              }
                            }
                          }
                        ]
                      }
                    }
                    
                    PUT my-index-000001/_doc/1
                    {
                      "name": {
                        "first":  "John",
                        "middle": "Winston",
                        "last":   "Lennon"
                      }
                    }
                    ```
                    
                - 혹은, 패턴 배열을 사용할 수도 있다.
                    
                    ```json
                    PUT my-index-000001
                    {
                      "mappings": {
                        "dynamic_templates": [
                          {
                            "full_name": {
                              "path_match":   ["name.*", "user.name.*"],
                              "path_unmatch": ["*.middle", "*.midinitial"],
                              "mapping": {
                                "type":       "text",
                                "copy_to":    "full_name"
                              }
                            }
                          }
                        ]
                      }
                    }
                    
                    PUT my-index-000001/_doc/1
                    {
                      "name": {
                        "first":  "John",
                        "middle": "Winston",
                        "last":   "Lennon"
                      }
                    }
                    
                    PUT my-index-000001/_doc/2
                    {
                      "user": {
                        "name": {
                          "first":      "Jane",
                          "midinitial": "M",
                          "last":       "Salazar"
                        }
                      }
                    }
                    ```
                    
        - Template variables
            
            ### Template variables
            
            - {name} 및 {dynamic_type} placeholder는 mapping에서 필드 이름 및 감지된 동적 유형으로 대체된다. 다음은 필드와 이름이 같은 Analyzer를 사용하도록 모든 문자열 필드를 설정하고 문자열이 아닌 모든 필드에 대해 doc_values를 비활성화한다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "dynamic_templates": [
                      {
                        "named_analyzers": {
                          "match_mapping_type": "string",
                          "match": "*",
                          "mapping": {
                            "type": "text",
                            "analyzer": "{name}"
                          }
                        }
                      },
                      {
                        "no_doc_values": {
                          "match_mapping_type":"*",
                          "mapping": {
                            "type": "{dynamic_type}",
                            "doc_values": false
                          }
                        }
                      }
                    ]
                  }
                }
                
                PUT my-index-000001/_doc/1
                {
                  "english": "Some English text", 
                  "count":   5 
                }
                ```
                
        - Dynamic Template examples
            - Structured search
                
                ### Structured search
                
                - “dynamic”:”true”로 설정하면 Elasticsearch는 문자열 필드를 하위 필드가 있는 필드로 매핑한다.
                - 구조화된 콘텐츠만 indexing하고 전체 텍스트 검색에 관심이 없을 경우에는 Elasticsearch가 필드를 keyword 필드로만 매핑하도록 할 수 있다.
                - 그러나 해당 필드를 검색하려면 indexing된 것과 정확히 동일한 값을 검색해야 한다.
                    
                    ```json
                    PUT my-index-000001
                    {
                      "mappings": {
                        "dynamic_templates": [
                          {
                            "strings_as_keywords": {
                              "match_mapping_type": "string",
                              "mapping": {
                                "type": "keyword"
                              }
                            }
                          }
                        ]
                      }
                    }
                    ```
                    
            - text-only mappings for strings
                
                ### text-only mappings for strings
                
                - 문자열 필드에 대한 전체 텍스트 검색에만 관심이 있고 집계, 정렬 또는 정확한 검색을 실행할 계획이 없다면 Elasticsearch에 다음과 같이 문자열을 text로 mapping하도록 지시할 수 있다.
                    
                    ```json
                    PUT my-index-000001
                    {
                      "mappings": {
                        "dynamic_templates": [
                          {
                            "strings_as_text": {
                              "match_mapping_type": "string",
                              "mapping": {
                                "type": "text"
                              }
                            }
                          }
                        ]
                      }
                    }
                    ```
                    
                - keyword 또는 문자열 필드를 mapping의 runtime section에 있는 필드로 매핑하는 dynamic templates를 생성할 수 있다.
                - Elasticsearch가 string 타입의 새 필드를 감지하면 해당 필드는 keyword 타입의 런타임 필드로 생성된다.
                - string 필드는 indexing되지 않지만 해당 값은 _source에 저장되며 검색 요청, 집계, 필터링 및 정렬에 사용할 수 있다.
                - 아래의 요청은 string 필드를 keyword 유형의 런타임 필드로 mapping하는 dynamic templates을 생성한다.
                    
                    ```json
                    PUT my-index-000001
                    {
                      "mappings": {
                        "dynamic_templates": [
                          {
                            "strings_as_keywords": {
                              "match_mapping_type": "string",
                              "runtime": {}
                            }
                          }
                        ]
                      }
                    }
                    ```
                    
            - Disabled norms
                
                ### Disabled norms
                
                - norms은 색인 시간 점수 요소이다. 예를 들어 문서를 점수별로 정렬하지 않는 경우와 같이 점수에 관심이 없는 경우에는 인덱스에서 이러한 점수 요소의 저장을 비활성화하고 일부 공간을 절약할 수 있다.
                    
                    ```json
                    PUT my-index-000001
                    {
                      "mappings": {
                        "dynamic_templates": [
                          {
                            "strings_as_keywords": {
                              "match_mapping_type": "string",
                              "mapping": {
                                "type": "text",
                                "norms": false,
                                "fields": {
                                  "keyword": {
                                    "type": "keyword",
                                    "ignore_above": 256
                                  }
                                }
                              }
                            }
                          }
                        ]
                      }
                    }
                    ```
                    
                - 하위 keyword 필드는 dynamic mapping의 기본 규칙과 일치하도록 이 templates에 나타난다.
            - Time series
                
                ### Time series
                
                - Elasticsearch로 Time series 분석을 수행할 때 집계하지만 필터링하지 않은 숫자 필드가 많은데, 이럴 경우 해당 필드에 대한 indexing을 비활성화하여 디스크 공간을 절약하고 indexing 속도를 높일 수 있다.
                    
                    ```json
                    PUT my-index-000001
                    {
                      "mappings": {
                        "dynamic_templates": [
                          {
                            "unindexed_longs": {
                              "match_mapping_type": "long",
                              "mapping": {
                                "type": "long",
                                "index": false
                              }
                            }
                          },
                          {
                            "unindexed_doubles": {
                              "match_mapping_type": "double",
                              "mapping": {
                                "type": "float", 
                                "index": false
                              }
                            }
                          }
                        ]
                      }
                    }
                    ```
                    
- Explicit mapping
    
    ### Create an index with an explicit mapping
    
    - create index API를 사용하여 명시적 mapping으로 새 인덱스를 생성할 수 있다.
        
        ```json
        PUT /my-index-000001
        {
          "mappings": {
            "properties": {
              "age":    { "type": "integer" },  
              "email":  { "type": "keyword"  }, 
              "name":   { "type": "text"  }     
            }
          }
        }
        ```
        
    
    ### Add a field to an existing mapping
    
    - update mapping API를 사용하여 하나 이상의 새 필드를 기존 인덱스에 추가할 수 있다.
    - 아래의 예에서 “index” 변수의 값이 false인 것은 필드 값이 저장되지만 indexing 되지 않거나 검색에 사용할 수 없음을 의미한다.
        
        ```json
        PUT /my-index-000001/_mapping
        {
          "properties": {
            "employee-id": {
              "type": "keyword",
              "index": false
            }
          }
        }
        ```
        
    
    ### Update the mapping of a field
    
    - 지원되는 mapping parameter를 제외하고 기존 필드의 mapping 또는 필드 유형을 변경할 수 없다. 기존 필드를 변경하면 이미 indexing된 데이터가 무효화될 수 있다.
    - 다른 인덱스에 있는 필드의 매핑을 변경해야 하는 경우 올바른 매핑으로 새 인덱스를 만들고 데이터를 해당 인덱스로 reindex 해야 한다.
    - 필드 이름을 바꾸면 이전 필드 이름으로 이미 indexing된 데이터는 무효화된다. 필드 이름을 변경하는 것이 아닌 alias 필드를 추가하여 대체 필드 이름을 만들어야 한다.
    
    ### View the mapping of an index
    
    - get mapping API를 사용하여 기존 인덱스의 mapping을 볼 수 있다.
        
        ```json
        GET /my-index-000001/_mapping
        ```
        
    - 반환 값은 다음과 같다.
        
        ```json
        {
          "my-index-000001" : {
            "mappings" : {
              "properties" : {
                "age" : {
                  "type" : "integer"
                },
                "email" : {
                  "type" : "keyword"
                },
                "employee-id" : {
                  "type" : "keyword",
                  "index" : false
                },
                "name" : {
                  "type" : "text"
                }
              }
            }
          }
        }
        ```
        
    
    ### View the mapping of specific fields
    
    - 하나 이상의 특정 필드에 대한 매핑만 볼 경우에는 get field mapping API를 사용할 수 있다.
    - 이는 인덱스의 전체 mapping이 필요하지 않거나 인덱스에 많은 수의 필드가 포함된 경우에 유용하다.
        
        ```json
        GET /my-index-000001/_mapping/field/employee-id
        ```
        
    - 반환 값은 다음과 같다.
        
        ```json
        {
          "my-index-000001" : {
            "mappings" : {
              "employee-id" : {
                "full_name" : "employee-id",
                "mapping" : {
                  "employee-id" : {
                    "type" : "keyword",
                    "index" : false
                  }
                }
              }
            }
          }
        }
        ```
        
- Runtime fields
    - Runtime fields
        
        ### Runtime fields
        
        - Runtime fields는 query time에 평가되는 필드다. Runtime field를 사용하여 다음 작업이 가능하다.
            - 데이터를 다시 indexing하지 않고 기존 문서에 필드 추가
            - 데이터 구조를 이해하지 못한 채 데이터 작업 시작
            - 쿼리 시 indexing된 필드에서 반환된 값 재정의
            - 기본 schema를 수정하지 않고 특정 용도로 필드 정의
        - search API에서 runtime fields에 액세스하고 Elasticsearch는 런타임 필드를 다르게 인식한다. index mapping 또는 search request에서 runtime fields를 정의할 수 있다.
        - Runtime fields는 로그 데이터로 작업할 때, 특히 데이터 구조가 확실하지 않을 때 유용하다. 검색 속도는 떨어지지만 인덱스 크기가 훨씬 작아서 로그를 indexing하지 않고도 더 빠르게 로그를 처리할 수 있다.
            
            ### Benefits
            
            - Runtime filed는 indexing되지 않기에 Runtime field를 추가해도 인덱스 크기가 늘어나지 않는다.
            - 인덱스 mapping에서 직접 runtime field를 정의하여 스토리지 비용을 절약하고 수집 속도를 높인다.
            - Runtime field를 정의하면 검색 요청, 집계, 필터링 및 정렬에서 즉시 사용할 수 있다.
            - Runtime field를 indexing된 필드로 변경하는 경우 Runtime field를 참조하는 쿼리를 수정할 필요 없다. 또한, 필드가 Runtime field인 일부 인덱스와 필드가 indexing된 다른 인덱스를 참조할 수 있다.
            - Runtime filed의 가장 중요한 이점은 필드를 수집한 후 문서에 필드를 추가하는 기능이다.
            
            ### Incentives
            
            - Runtime field는 _search API에서 스크립팅을 사용할 수 있는 많은 방법을 대체할 수 있다. Runtime field를 사용하는 방법은 포함된 스크립트가 실행되는 문서 수의 영향을 받는다.
            - 스크립트 필드를 사용하여 _source 값에 액세스하고 스크립트 평가를 기반으로 계산된 값을 반환할 수 있다. 단, 스크립트 필드는 값만 가져올 수 있다.
            - 스크립트를 기반으로 검색 요청에서 문서를 필터링하는 Script query를 작성할 수 있다. Runtime field는 보다 유연한, 매우 유사한 기능을 제공한다.
            
            ### Compromises
            
            - Runtime field는 디스크 공간을 덜 사용하고 데이터에 액세스 하는 방법에 유연성을 제공하지만, Runtime script에 정의된 계산을 기반으로 검색 성능에 영향을 미칠 수 있다.
            - 검색 성능과 유연성의 균형을 맞추려면 Timestamp와 같이 자주 검색하고 필터링하는 필드를 indexing 해야 한다. Elasticsearch는 쿼리를 실행할 때 자동으로 indexing된 필드를 먼저 사용하므로 응답 시간이 빠르다. 그 뒤 Runtime field를 사용하여 Elasticsearch가 값을 계산하는 데 필요한 필드 수를 제한할 수 있다.
            
            <aside>
            💡 search.allow_expensive_queries를 false로 설정하면 비용이 많이 드는 쿼리가 허용되지 않으며 Elasticsearch는 런타임 필드에 대한 모든 쿼리를 거부한다.
            
            </aside>
            
    - Map a runtime field
        
        ### Map a runtime field
        
        - Mapping 정의 아래 runtime section을 추가 하고 Painless script를 정의하여 runtime field를 mapping한다.
        - 이 스크립트는 params._source를 통한 원본 _source 및 mapping된 필드와 해당 값을 포함하여 문서의 전체 Context에 액세스할 수 있다. query time에 스크립트가 실행되고 쿼리에 필요한 각 scripting된 필드에 대한 값을 생성한다.
        - 아래의 스크립트는 date 타입으로 정의된 timestamp 필드에서 요일을 계산한다. 스크립트는 timestamp의 값을 기준으로 요일을 계산하고 emit을 사용하여 계산된 값을 반환한다.
            
            ```json
            PUT my-index-000001/
            {
              "mappings": {
                "runtime": {
                  "day_of_week": {
                    "type": "keyword",
                    "script": {
                      "source": "emit(doc['@timestamp'].value.dayOfWeekEnum.getDisplayName(TextStyle.FULL, Locale.ROOT))"
                    }
                  }
                },
                "properties": {
                  "@timestamp": {"type": "date"}
                }
              }
            }
            ```
            
        - 동적 매개변수가 runtime으로 설정된 동적 필드 mapping이 활성화된 경우 새 필드가 런타임 필드로 인덱스 mapping에 자동으로 추가된다.
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "dynamic": "runtime",
                "properties": {
                  "@timestamp": {
                    "type": "date"
                  }
                }
              }
            }
            ```
            
            ### Define runtime fields without a script
            
            - Runtime field에는 일반적으로 어떤 방식으로든 데이터를 조작하는 Painless script가 포함된다. 그러나 스크립트 없이 runtime field를 정의할 수 있는 instance가 있다.  예를 들어 _source를 변경하지 않고 단일 필드를 검색하려는 경우 스크립트는 필요하지 않다.
                
                ```json
                PUT my-index-000001/
                {
                  "mappings": {
                    "runtime": {
                      "day_of_week": {
                        "type": "keyword"
                      }
                    }
                  }
                }
                ```
                
            - 스크립트가 제공되지 않으면 Elasticsearch는 쿼리 시간에 _source에서 runtime field와 이름이 같은 필드를 암시적으로 찾고 값이 있으면 값을 반환한다.
            - 대부분의 경우, doc_values를 통해 가능할 때마다 필드 값을 검색하는 편이 유리하다. 데이터가 Lucene에서 로드되는 방식 때문에 runtime filed를 사용하여 doc_values에 액세스하는 것이 _source에서 값을 검색하는 것보다 빠르다.
            - 단, 예를 들어 text 필드처럼 기본적으로 사용 가능한 doc_values가 없는 경우에는 _source에서 값을 검색해야 한다. 다른 경우로는 특정 필드에서 doc_values를 비 활성화하도록 선택한 경우.
            - 혹은, 값을 검색하려는 필드에 params._source (ex; params._source.day_of_week :) 접두사를 붙일 수 있다. 단순화를 위해 가능한 경우 스크립트 없이 ampping 정의에서 runtime field를 정의하는 것이 권장되는 옵션이다.
            
            ### Ignoring script errors on runtime fields
            
            - on_script_error 매개변수는 오류 동작을 제어하는 데 사용할 수 있다. on_script_error 매개변수를 continue로 설정하면 런타임 필드의 모든 오류를 자동으로 무시하는 효과가 있다. 기본 값 fail은 검색 응답에서 보고되는 shard 실패를 유발한다.
            
            ### Updaing and removing runtime fields
            
            - runtime field는 언제든지 업데이트하거나 제거할 수 있다. 기존 runtime field를 바꾸려면 동일한 이름의 mapping에 새 runtime fields를 추가하고, mapping에서 runtime field를 제거하려면 필드의 값을 null로 설정하면 된다.
                
                ```json
                PUT my-index-000001/_mapping
                {
                 "runtime": {
                   "day_of_week": null
                 }
                }
                ```
                
                <aside>
                💡 종속 쿼리가 실행되는 동안 runtime field를 업데이트하거나 제거하면 일관성 없는 결과가 반환될 수 있다. 각 shard는 mapping 변경이 적용되는 시기에 따라 서로 다른 버전의 스크립트에 액세스할 수 있다.
                
                </aside>
                
    - Define runtime fields in a search request
        
        ### Define runtime fields in a search request
        
        - 검색 요청에서 runtime_mapping section을 지정하여 쿼리의 일부로만 존재하는 runtime field를 생성할 수 있다.
        - 검색 요청에서 runtime field를 정의하는 것은 index mapping에서 runtime field를 정의하는 것과 동일한 형식을 사용한다.
        - runtime_mappings section에 day_of_week 필드를 추가하는 검색 요청. 필드 값은 이 검색 요청의 context 내에서만 동적으로 계산된다.
            
            ```json
            GET my-index-000001/_search
            {
              "runtime_mappings": {
                "day_of_week": {
                  "type": "keyword",
                  "script": {
                    "source": "emit(doc['@timestamp'].value.dayOfWeekEnum.getDisplayName(TextStyle.FULL, Locale.ROOT))"
                  }
                }
              },
              "aggs": {
                "day_of_week": {
                  "terms": {
                    "field": "day_of_week"
                  }
                }
              }
            }
            ```
            
            ### Create runtime fields that use other runtime fields
            
            - 다른 runtime fields에서 값을 반환하는 검색 요청에서 runtime fields를 정의할 수도 있다.
                
                ```json
                PUT my-index-000001/_mapping
                {
                  "runtime": {
                    "measures.start": {
                      "type": "long"
                    },
                    "measures.end": {
                      "type": "long"
                    }
                  }
                }
                ```
                
            - runtime field는 인덱스 mapping에서 동일한 이름으로 정의된 필드보다 우선한다. 이러한 유연성을 통해 필드 자체를 수정하지 않고도 기존 필드를 숨기고 다른 값을 계산할 수 있다. 인덱스 mapping에서 실수한 경우 runtime field를 사용하여 검색 요청 중에 mapping의 값을 재 정의하는 값을 계산할 수 있다.
            - measures.start와 measures.end 필드에서 평균 집계를 쉽게 실행할 수 있다.
                
                ```json
                GET my-index-000001/_search
                {
                  "aggs": {
                    "avg_start": {
                      "avg": {
                        "field": "measures.start"
                      }
                    },
                    "avg_end": {
                      "avg": {
                        "field": "measures.end"
                      }
                    }
                  }
                }
                ```
                
            - 응답에는 기본 데이터의 값을 변경하지 않고 집계 결과가 포함된다.
                
                ```json
                {
                  "aggregations" : {
                    "avg_start" : {
                      "value" : 333.3333333333333
                    },
                    "avg_end" : {
                      "value" : 8658642.333333334
                    }
                  }
                }
                ```
                
            - 또한, 값을 계산하는 검색 쿼리의 일부로 runtime fields를 정의한 다음 동일한 쿼리의 해당 필드에서 통계 집계를 실행할 수도 있다.
    - Override field values at query time
        
        ### Override field values at query time
        
        - mapping에 이미 있는 필드와 이름이 같은 runtime field를 생성하면 runtime field가 mapping된 필드를 가리게 된다.
        - 쿼리 시간에 Elasticsearch는 런타임 필드를 평가하고 스크립트를 기반으로 값을 계산하며 쿼리의 일부로 값을 반환한다.
        - runtime fields는 mapping된 필드를 가리기 때문에 mapping된 필드를 수정하지 않고 검색에서 반환된 값을 재정의할 수 있다.
            
            ```json
            GET my-index-000001/_search
            {
              "query": {
                "match": {
                  "model_number": "HG537PU"
                }
              }
            }
            ```
            
        - _search API의 fields 매개변수를 사용하면 스크립트가 검색 요청과 일치하는 문서의 특정 필드에 대해 계산하는 값을 검색할 수 있다.
            
            ```json
            POST my-index-000001/_search
            {
              "runtime_mappings": {
                "measures.voltage": {
                  "type": "double",
                  "script": {
                    "source":
                    """if (doc['model_number.keyword'].value.equals('HG537PU'))
                    {emit(1.7 * params._source['measures']['voltage']);}
                    else{emit(params._source['measures']['voltage']);}"""
                  }
                }
              },
              "query": {
                "match": {
                  "model_number": "HG537PU"
                }
              },
              "fields": ["measures.voltage"]
            }
            ```
            
    - Retrieve a runtime field
        
        ### Retrieve a runtime field
        
        - _searchAPI에서 fields 매개변수를 사용하여 runtime field의 값을 검색할 수 있다. runtime field에 _source는 표시되지 않지만, fiedls API는 _source의 일부로 전송되지 않은 필드를 포함하여 모든 필드에서 작동한다.
            
            ### Define a runtime field to calculate the day of week
            
            - day_of_week runtime field를 추가하기 위한 요청
                
                ```json
                PUT my-index-000001/
                {
                  "mappings": {
                    "dynamic": "runtime",
                    "runtime": {
                      "day_of_week": {
                        "type": "keyword",
                        "script": {
                          "source": "emit(doc['@timestamp'].value.dayOfWeekEnum.getDisplayName(TextStyle.FULL, Locale.ROOT))"
                        }
                      }
                    },
                    "properties": {
                      "@timestamp": {"type": "date"}
                    }
                  }
                }
                ```
                
            
            ### Search for the calculated day of week
            
            - 다음 요청은 search API를 사용하여 원래 요청이 mapping에서 runtime field로 정의한 day_of_week 필드를 검색한다. 이 필드의 값은 문서를 다시 indexing하거나 day_of_week 필드를 indexing하지 않고 쿼리 시간에 동적으로 계산된다.
            - 이러한 유연성을 통해 필드 값을 변경하지 않고 mapping을 수정할 수 있다.
                
                ```json
                GET my-index-000001/_search
                {
                  "fields": [
                    "@timestamp",
                    "day_of_week"
                  ],
                  "_source": false
                }
                ```
                
            - message 필드에서도 작동하는 client_ip라는 또 다른 runtime field를 정의할 수 있으며 쿼리를 더욱 세분화할 수 있다.
                
                ```json
                PUT /my-index-000001/_mapping
                {
                  "runtime": {
                    "client_ip": {
                      "type": "ip",
                      "script" : {
                      "source" : "String m = doc[\"message\"].value; int end = m.indexOf(\" \"); emit(m.substring(0, end));"
                      }
                    }
                  }
                }
                ```
                
            
            ### Retrieve fields from related indices
            
            <aside>
            💡 미리 보기 상태인 기술이며 향후 릴리스에서 변경되거나 제거될 수 있다.
            
            </aside>
            
            - _search API의 fields 매개변수는 lookup 유형이 있는 런타임 필드를 통해 관련 인덱스에서 필드를 검색할 수 있다.
                
                ```json
                POST ip_location/_doc?refresh
                {
                  "ip": "192.168.1.1",
                  "country": "Canada",
                  "city": "Montreal"
                }
                
                PUT logs/_doc/1?refresh
                {
                  "host": "192.168.1.1",
                  "message": "the first message"
                }
                
                PUT logs/_doc/2?refresh
                {
                  "host": "192.168.1.2",
                  "message": "the second message"
                }
                
                POST logs/_search
                {
                  "runtime_mappings": {
                    "location": {
                        "type": "lookup", 
                        "target_index": "ip_location", 
                        "input_field": "host", 
                        "target_field": "ip", 
                        "fetch_fields": ["country", "city"] 
                    }
                  },
                  "fields": [
                    "host",
                    "message",
                    "location"
                  ],
                  "_source": false
                }
                ```
                
            - 응답 값은 다음과 같다.
                
                ```json
                {
                  "took": 3,
                  "timed_out": false,
                  "_shards": {
                    "total": 1,
                    "successful": 1,
                    "skipped": 0,
                    "failed": 0
                  },
                  "hits": {
                    "total": {
                      "value": 2,
                      "relation": "eq"
                    },
                    "max_score": 1.0,
                    "hits": [
                      {
                        "_index": "logs",
                        "_id": "1",
                        "_score": 1.0,
                        "fields": {
                          "host": [ "192.168.1.1" ],
                          "location": [
                            {
                              "city": [ "Montreal" ],
                              "country": [ "Canada" ]
                            }
                          ],
                          "message": [ "the first message" ]
                        }
                      },
                      {
                        "_index": "logs",
                        "_id": "2",
                        "_score": 1.0,
                        "fields": {
                          "host": [ "192.168.1.2" ],
                          "message": [ "the second message" ]
                        }
                      }
                    ]
                  }
                }
                ```
                
            - 조회 필드의 응답은 조회 색인에서 각 문서의 독립성을 유지하기 위해 그룹화된다. 각 입력 값에 대한 조회 쿼리는 조회 인덱스에서 최대 하나의 문서와 일치할 것으로 예상된다. 조회 쿼리가 둘 이상의 문서와 일치하면 임의의 문서가 선택된다.
    - Index a runtime field
        
        ### Index a runtime field
        
        - runtime field는 실행되는 context에 의해 정의된다.
        - Elasticsearch는 이러한 indexing된 필드를 자동으로 사용하여 쿼리를 구동하므로 응답 시간이 빨라진다. 이 기능은 스크립트를 한 번만 작성하고 runtime filed를 지원하는 모든 conetxt에 적용할 수 있음을 의미한다.
            
            <aside>
            💡 composite indexing runtime field는 현재 지원되지 않는다.
            
            </aside>
            
        - runtime filed를 사용하여 Elasticsearch가 값을 계산하는 데 필요한 필드 수를 제한할 수 있다. runtime field와 함께 indexing된 필드를 사용하면 indexing하는 데이터와 다른 필드에 대한 쿼리를 정의하는 방법에 유연성이 제공된다.
            
            <aside>
            💡 runtime filed를 indexing한 후에는 포함된 스크립트를 업데이트할 수 없다.
            
            </aside>
            
        - 현재 소스로부터 계산하여 새로운 runtime field를 생성하는 방식은 다음과 같다.
            
            ```json
            PUT my-index-000001/_mapping
            {
              "runtime": {
                "voltage_corrected": {
                  "type": "double",
                  "script": {
                    "source": """
                    emit(doc['voltage'].value * params['multiplier'])
                    """,
                    "params": {
                      "multiplier": 2
                    }
                  }
                }
              }
            }
            ```
            
        - 스크립트가 인덱스 시간(기본 값)에 오류를 발생 시키는 경우, 전체 문서를 거부할지 여부를 결정하는 on_script_error라는 선택적 매개변수를 추가할 수 있다.
            
            ```json
            PUT my-index-000001/
            {
              "mappings": {
                "properties": {
                  "timestamp": {
                    "type": "date"
                  },
                  "temperature": {
                    "type": "long"
                  },
                  "voltage": {
                    "type": "double"
                  },
                  "node": {
                    "type": "keyword"
                  },
                  "voltage_corrected": {
                    "type": "double",
                    "on_script_error": "fail", 
                    "script": {
                      "source": """
                    emit(doc['voltage'].value * params['multiplier'])
                    """,
                      "params": {
                        "multiplier": 4
                      }
                    }
                  }
                }
              }
            }
            ```
            
    - Explore your data with runtime fields
        
        ### Explore your data with runtime fields
        
        - 필드를 추출하려는 대규모 로그 데이터 세트를 고려할 때, 데이터 Indexing은 시간이 많이 걸리고 많은 디스크 공간을 사용하며 미리 Schema를 commit하지 않고 데이터 구조를 탐색하기만 하면 된다.
            
            ### Ingest some data
            
            - 검색하려는 필드를 mapping한 후 로그 데이터의 몇 가지 레코드를 Elasticsearch로 indexing한다. 다음 요청은 bulk API를 사용하여 원시 로그 데이터를 my-index-000001로 indexing한다. 모든 로그 데이터를 indexing하는 대신 작은 샘플을 사용하여 runtime field를 실험할 수도 있다.
                
                ```json
                POST /my-index-000001/_bulk?refresh
                {"index":{}}
                {"timestamp":"2020-04-30T14:30:17-05:00","message":"40.135.0.0 - - [30/Apr/2020:14:30:17 -0500] \"GET /images/hm_bg.jpg HTTP/1.0\" 200 24736"}
                {"index":{}}
                {"timestamp":"2020-04-30T14:30:53-05:00","message":"232.0.0.0 - - [30/Apr/2020:14:30:53 -0500] \"GET /images/hm_bg.jpg HTTP/1.0\" 200 24736"}
                {"index":{}}
                {"timestamp":"2020-04-30T14:31:12-05:00","message":"26.1.0.0 - - [30/Apr/2020:14:31:12 -0500] \"GET /images/hm_bg.jpg HTTP/1.0\" 200 24736"}
                {"index":{}}
                {"timestamp":"2020-04-30T14:31:19-05:00","message":"247.37.0.0 - - [30/Apr/2020:14:31:19 -0500] \"GET /french/splash_inet.html HTTP/1.0\" 200 3781"}
                {"index":{}}
                {"timestamp":"2020-04-30T14:31:22-05:00","message":"247.37.0.0 - - [30/Apr/2020:14:31:22 -0500] \"GET /images/hm_nbg.jpg HTTP/1.0\" 304 0"}
                {"index":{}}
                {"timestamp":"2020-04-30T14:31:27-05:00","message":"252.0.0.0 - - [30/Apr/2020:14:31:27 -0500] \"GET /images/hm_bg.jpg HTTP/1.0\" 200 24736"}
                {"index":{}}
                {"timestamp":"2020-04-30T14:31:28-05:00","message":"not a valid apache log"}
                ```
                
            
            ### Define a runtime field with a grok pattern
            
            - clientip를 포함하는 결과를 검색하려는 경우 해당 필드를 mapping의 runtime field로 추가할 수 있다. 다음 runtime script는 문서 내의 단일 텍스트 필드에서 구조화된 필드를 추출하는 grok 패턴을 정의한다.
            - grok 필드는 재 사용할 수 있는 별칭 표현식을 지원하는 정규식과 같다.
            - 스크립트는 Apache 로그의 구조를 이해하는 ${COMMONAPACHELOG} 로그 패턴과 일치한다. 패턴이 일치하면 (clientip ≠ null) script는 일치하는 IP 주소의 값을 내보낸다. 패턴이 일치하지 않아도 스크립트는 충돌 없이 필드 값만 반환한다.
                
                ```json
                PUT my-index-000001/_mappings
                {
                  "runtime": {
                    "http.client_ip": {
                      "type": "ip",
                      "script": """
                        String clientip=grok('%{COMMONAPACHELOG}').extract(doc["message"].value)?.clientip;
                        if (clientip != null) emit(clientip); 
                      """
                    }
                  }
                }
                ```
                
            - 또는 검색 요청 context에서 동일한 runtime field를 정의할 수 있다. runtime 정의와 스크립트는 index mapping에서 이전에 정의한 것과 정확히 동일하다면 해당 정의를 runtime_mappings section 아래의 검색 요청에 복사하고 runtime field에서 일치하는 쿼리를 포함하기만 하면 된다.
                
                ```json
                GET my-index-000001/_search
                {
                  "runtime_mappings": {
                    "http.clientip": {
                      "type": "ip",
                      "script": """
                        String clientip=grok('%{COMMONAPACHELOG}').extract(doc["message"].value)?.clientip;
                        if (clientip != null) emit(clientip);
                      """
                    }
                  },
                  "query": {
                    "match": {
                      "http.clientip": "40.135.0.0"
                    }
                  },
                  "fields" : ["http.clientip"]
                }
                ```
                
            
            ### Define a composite runtime field
            
            - 복합 런타임 필드를 정의하여 단일 스크립트에서 여러 필드를 내보낼 수도 있다.
            - 혹은 유형이 지정된 하위 필드 세트를 정의하고 map of values를 내보낼 수도 있다.
            - 검색 시각 하위 필드는 맵에서 해당 이름과 연결된 값을 검색한다.
            - 즉, grok 패턴을 한 번만 지정하면 되고 여러 값을 반환할 수 있다.
                
                ```json
                PUT my-index-000001/_mappings
                {
                  "runtime": {
                    "http": {
                      "type": "composite",
                      "script": "emit(grok(\"%{COMMONAPACHELOG}\").extract(doc[\"message\"].value))",
                      "fields": {
                        "clientip": {
                          "type": "ip"
                        },
                        "verb": {
                          "type": "keyword"
                        },
                        "response": {
                          "type": "long"
                        }
                      }
                    }
                  }
                }
                ```
                
            
            ### Search for a specific IP address
            
            - http.clientip runtim field를 사용하면 특정 IP 주소에 대한 검색을 실행하고 모든 관련 필드를 반환하는 간단한 쿼리를 정의할 수 있다.
                
                ```json
                GET my-index-000001/_search
                {
                  "query": {
                    "match": {
                      "http.clientip": "40.135.0.0"
                    }
                  },
                  "fields" : ["*"]
                }
                ```
                
            - http는 composite runtim filed이기 때문에 응답에는 쿼리와 일치하는 연결된 값을 포함하여 필드 아래의 각 하위 필드가 포함된다.
            - 데이터 구조를 미리 구축하지 않고도 의미 있는 방식으로 데이터를 검색하고 탐색하여 indexing할 필드를 결정하고 실험할 수 있다.
                
                ```json
                {
                  ...
                  "hits" : {
                    "total" : {
                      "value" : 1,
                      "relation" : "eq"
                    },
                    "max_score" : 1.0,
                    "hits" : [
                      {
                        "_index" : "my-index-000001",
                        "_id" : "sRVHBnwBB-qjgFni7h_O",
                        "_score" : 1.0,
                        "_source" : {
                          "timestamp" : "2020-04-30T14:30:17-05:00",
                          "message" : "40.135.0.0 - - [30/Apr/2020:14:30:17 -0500] \"GET /images/hm_bg.jpg HTTP/1.0\" 200 24736"
                        },
                        "fields" : {
                          "http.verb" : [
                            "GET"
                          ],
                          "http.clientip" : [
                            "40.135.0.0"
                          ],
                          "http.response" : [
                            200
                          ],
                          "message" : [
                            "40.135.0.0 - - [30/Apr/2020:14:30:17 -0500] \"GET /images/hm_bg.jpg HTTP/1.0\" 200 24736"
                          ],
                          "http.client_ip" : [
                            "40.135.0.0"
                          ],
                          "timestamp" : [
                            "2020-04-30T19:30:17.000Z"
                          ]
                        }
                      }
                    ]
                  }
                }
                ```
                
            
            ### Define a runtime field with a dissect pattern
            
            - 정규식의 기능이 필요하지 않은 경우에는 gork 패턴 대신 dissect 패턴을 사용할 수 있다. dissect 패턴은 고정 구분 기호와 일치하지만 일반적으로 gork보다 빠르다.
            - gork 패턴으로 Apache 로그를 구문 분석하는 것과 동일한 결과를 얻는 dissect 패턴 사용은 다음과 같다. 이는 로그 패턴에서 일치하는 대신 삭제하려는 문자열 부분을 포함한다.
            
            ```json
            PUT my-index-000001/_mappings
            {
              "runtime": {
                "http.client.ip": {
                  "type": "ip",
                  "script": """
                    String clientip=dissect('%{clientip} %{ident} %{auth} [%{@timestamp}] "%{verb} %{request} HTTP/%{httpversion}" %{status} %{size}').extract(doc["message"].value)?.clientip;
                    if (clientip != null) emit(clientip);
                  """
                }
              }
            }
            ```
            
- Field data types
    - Field data types
        
        ### Field data types
        
        - 각 필드에는 field data type 또는 field type이 있다. 이 유형은 문자열 또는 boolean 값과 같은 필드에 포함된 데이터의 종류와 의도된 용도를 나타낸다.
        - 필드 유형은 family 별로 그룹화된다. 같은 계열의 유형은 정확히 동일한 검색 동작을 갖지만 공간 사용량이나 성능 특성이 다를 수 있다.
        - 현재 text, keyword 두 가지의 유형 family가 있으며 다른 유형 family에는 단일 필드 유형만 있다.
            
            ### Common types
            
            - binary : Base64 문자열로 인코딩된 이진 값
            - boolean : true와 false 값
            - Keywords : keyword, constant_keyword, wildcard를 포함하는 keyword family
            - Numbers : Numeric types, amount를 표현하는데 사용되는 long이나 double같은 숫자 유형
            - Dates : date 및 date_nanos를 포함한 날짜 유형
            - alias : 기존 필드의 별칭을 정의
            
            ### Objects and relational types
            
            - object : JSON 객체
            - flattened : 단일 필드 값인 전체 JSON 객체
            - nested : 하위 필드 간의 관계를 유지하는 JSON 객체
            - join : 동일한 색인에 있는 문서의 상위/하위 관계를 정의
            
            ### Structured data types
            
            - Range : long_range, double_range, date_range, ip_range를 포함한 Range_type
            - ip : IPv4, IPv6 addresses
            - version : 소프트웨어 버전. Semantic Versioning 우선 순위 규칙을 지원
            - murmur3 : 값의 hash를 계산하고 저장
            
            ### Aggregate data types
            
            - aggregate_metric_double : 사전 집계된 측정 항목 값
            - histogram : 히스토그램 형태의 사전 집계된 숫자 값
            
            ### Text search types
            
            - text fields : text, match_only_text를 포함하는 텍스트 family. 분석되고 구조화되지 않은 텍스트
            - annotated-text : 특수 markup이 포함된 텍스트. 명명된 entitiy를 식별하는 데 사용
            - completion : 자동 완성 제안에 사용
            - search_as_you_type : text- 유형 완성을 위한 유사 유형
            - token_count : 텍스트의 토큰 수
            
            ### Document ranking types
            
            - dense_vector : 부동 소수점 값의 조밀한 벡터를 기록
            - rank_feature : 쿼리 시간에 적중률을 높이는 숫자 기능을 기록
            - rank_features : 쿼리 시간에 적중률을 높이기 위해 숫자 기능을 기록
            
            ### Spatial data types
            
            - geo_point : 위도 및 경도 지점
            - geo_shape : 다각형과 같은 복잡한 모양
            - point : 임의 cartesian 점
            - shape : 임의 cartesian 기하학
            
            ### Other types
            
            - precolator : Query DSL로 작성된 쿼리를 indexing
            
            ### Arrays
            
            - Elasticsearch에서 배열에는 전용 필드 데이터 유형이 필요하지 않다. 모든 필드는 기본적으로 0개 이상의 값을 포함할 수 있지만 배열의 모든 값은 동일한 필드 유형이어야 한다.
            
            ### Multi-fields
            
            - 서로 다른 목적을 위해 서로 다른 방식으로 동일한 필드를 indexing하는 것이 Multi-fields의 목적이다. 대부분의 필드 유형은 fields 매개변수를 통해 다중 필드를 지원한다.
    - Aggregate metric
        
        ### Aggregate metric
        
        - aggregate_metric_double 필드는 min, max, sum, value_count metric 하위 필드 중 하나 이상을 포함하는 개체다.
        - aggregate_metric_double 필드에서 특정 metric 집계를 실행할 때 집계는 관련 하위 필드의 값을 사용한다.
        - aggregate_metric_double 필드는 각 metric 하위 필드에 대한 단일 숫자 문서 값을 저장한다. 배열 값은 지원되지 않는다. 최소, 최대 및 합계 값은 dobule 타입이다. value는 long 타입이다.
            
            ```json
            PUT my-index
            {
              "mappings": {
                "properties": {
                  "my-agg-metric-field": {
                    "type": "aggregate_metric_double",
                    "metrics": [ "min", "max", "sum", "value_count" ],
                    "default_metric": "max"
                  }
                }
              }
            }
            ```
            
            ### Parameters for aggregate_metric_double fields
            
            - metrics
                - 필수, 문자열 배열.
                - 저장할 metric 하위 필드의 배열.
                - 각 값은 metric aggregation에 해당한다.
                - 유효한 값은 min, max, sum, value_count.
                - 하나 이상의 값을 지정해야 한다.
            - default_metric
                - 필수, 문자열
                - 하위 필드를 사용하지 않는 쿼리, 스크립트 및 집계에 사용할 기본 metric 하위 필드
                - metrics 배열의 값이어야 한다.
            - time_series_metric
                - 선택, 문자열
                - 필드를 time series metric으로 표시한다. 값은 metric 유형.
                - 기존 필드에 대해 이 매개변수를 업데이트할 수 없다.
            
            ### Uses
            
            - min aggregation은 모든 min 하위 필드 중 최솟값을 반환한다.
            - max aggregation은 모든 max 하위 필드 중 최댓값을 반환한다.
            - sum aggreateion은 모든 sum 하위 필드의 합계를 반환한다.
            - value_count aggregation은 모든 value_count 하위 필드의 합계를 반환한다.
            - avg aggreation의 경우, avg 하위 필드가 존재하지 않기에 avg aggregation은 sum과 value_count metric을 이용하여 aggregation한다. avg aggregation을 실행하기 위해선 value_count와 sum metric 하위 필드가 모두 포함되어 있어야 한다.
            - aggregate_metric_duble 필드는 해당 동작을 default_metric 하위 필드에 위임하여 double로 동작하는 다음 쿼리를 지원한다.
                - exists
                - range
                - term
                - terms
            
            ### Examples
            
            - 다음 create index API 요청은 agg_metric이라는 aggregate_metric_double 필드가 있는 인덱스를 생성한다. 요청은 max를 필드의 default_metric으로 설정한다.
                
                ```json
                PUT stats-index
                {
                  "mappings": {
                    "properties": {
                      "agg_metric": {
                        "type": "aggregate_metric_double",
                        "metrics": [ "min", "max", "sum", "value_count" ],
                        "default_metric": "max"
                      }
                    }
                  }
                }
                ```
                
            - agg_metric 필드에서 min, max, sum, value_count, avg를 실행하는 방법은 다음과 같다.
                
                ```json
                POST stats-index/_search?size=0
                {
                  "aggs": {
                    "metric_min": { "min": { "field": "agg_metric" } },
                    "metric_max": { "max": { "field": "agg_metric" } },
                    "metric_value_count": { "value_count": { "field": "agg_metric" } },
                    "metric_sum": { "sum": { "field": "agg_metric" } },
                    "metric_avg": { "avg": { "field": "agg_metric" } }
                  }
                }
                ```
                
            
            ### Synthetic _source
            
            <aside>
            💡 Synthetic _source는 TSDB 인덱스(index.mode가 time_series로 설정된 인덱스)에 대해서만 일반적으로 사용 가능하다.
            
            Synthetic _source는 preview 기술로 추후 제거될 수 있다.
            
            </aside>
            
            - aggregate_metric_double 필드는 기본 구성에서 synthetic _source를 지원한다.  Synthetic source는 ignore_malformed와 함께 사용할 수 없다.
            - 예를 들어 아래의 요청은
                
                ```json
                PUT idx
                {
                  "mappings": {
                    "_source": { "mode": "synthetic" },
                    "properties": {
                      "agg_metric": {
                        "type": "aggregate_metric_double",
                        "metrics": [ "min", "max", "sum", "value_count" ],
                        "default_metric": "max"
                      }
                    }
                  }
                }
                
                PUT idx/_doc/1
                {
                  "agg_metric": {
                    "min": -302.50,
                    "max": 702.30,
                    "sum": 200.0,
                    "value_count": 25
                  }
                }
                ```
                
            - 다음 반환 값을 받는다.
                
                ```json
                {
                  "agg_metric": {
                    "min": -302.50,
                    "max": 702.30,
                    "sum": 200.0,
                    "value_count": 25
                  }
                }
                ```
                
    - Alias
        
        ### Alias
        
        - alias mapping은 필드에 대한 대체 이름을 정의한다.
            
            ```json
            PUT trips
            {
              "mappings": {
                "properties": {
                  "distance": {
                    "type": "long"
                  },
                  "route_length_miles": {
                    "type": "alias",
                    "path": "distance" 
                  },
                  "transit_mode": {
                    "type": "keyword"
                  }
                }
              }
            }
            
            GET _search
            {
              "query": {
                "range" : {
                  "route_length_miles" : {
                    "gte" : 39
                  }
                }
              }
            }
            ```
            
        - 검색 요청의 거의 모든 구성 요소는 filed alias를 허용한다. 특히 alias는 쿼리, aggregations, sort fiedls, docvalue_fields, stored_fields, request 및 highlights를 요청할 때도 사용할 수 있다.
        - 스크립트는 필드 값에 액세스할 때 별칭도 지원한다.
        - 검색 요청의 일부와 필드 기능을 요청할 때 필드 wildcard 패턴을 제공할 수 있다. 이러한 경우 wildcard 패턴은 구체적인 필드 외에도 필드 alias와 일치한다.
            
            ```json
            GET trips/_field_caps?fields=route_*,transit_mode
            ```
            
            ### Alias targets
            
            - alias target에는 몇 가지 제한 사항이 있다.
                - 대상은 개체나 다른 필드 alias가 아닌 구체적인 필드여야 한다.
                - 대상 필드는 alias이 생성될 때 존재해야 한다.
                - 중첩 개체가 정의된 경우 필드 alias는 대상과 동일한 중첩 범위를 가져야 한다.
                - 필드 alias는 하나의 대상만 가질 수 있다.
            
            ### Unsupported APIs
            
            - 필드 별칭에 대한 쓰기는 지원되지 않는다. 인덱스 또는 업데이트 요청에서 별칭을 사용하려고 하면 실패한다. 마찬가지로 별칭은 다중 필드의 대상 또는 copy_to 필드에서 사용할 수 없다.
            - 8.9 버전을 기준으로, 검색 및 필드 기능 API만 필드 별칭을 수락하고 해결한다.
            - term, geo_shape, more_like_this와 같은 일부 쿼리를 사용하면 indexing된 문서에서 쿼리 정보를 가져올 수 있다. 문서를 가져올 때 필드 별칭이 지원되지 않기에 조회 경로를 지정하는 쿼리 부분은 해당 별칭으로 쿼리를 참조할 수 없다.
    - Arrays
        
        ### Arrays
        
        - Elasticsearch에는 전용 array 데이터 배열이 없다. 모든 필드는 기본적으로 0개 이상의 값을 포함할 수 있지만 배열의 모든 값은 동일한 데이터 유형이어야 한다.
            
            <aside>
            💡 배열의 다른 객체와 독립적으로 각 객체를 query할 수 없다. 이를 수행할 수 있어야 하는 경우 객체 데이터 유형 대신 nested 데이터 유형을 사용해야 한다.
            
            </aside>
            
        - 필드를 동적으로 추가할 때는 배열의 첫 번째 값이 필드의 타입을 결정한다. 모든 후속 값은 동일한 데이터 유형이거나, 최소한 후속 값을 동일한 데이터 유형으로 강제 형변환할 수 있어야 한다.
        - 데이터 유형이 혼합된 배열은 지원 되지 않는다.
        - 배열에는 구성된 null_value로 대체되거나 완전히 건너뛰는 null값이 포함될 수 있다. 빈 배열은 누락된 필드(값이 없는 필드)로 취급된다.
        - 배열은 기본적으로 지원된다.
            
            ```json
            PUT my-index-000001/_doc/1
            {
              "message": "some arrays in this document...",
              "tags":  [ "elasticsearch", "wow" ], 
              "lists": [ 
                {
                  "name": "prog_list",
                  "description": "programming list"
                },
                {
                  "name": "cool_list",
                  "description": "cool stuff list"
                }
              ]
            }
            
            PUT my-index-000001/_doc/2 
            {
              "message": "no arrays in this document...",
              "tags":  "elasticsearch",
              "lists": {
                "name": "prog_list",
                "description": "programming list"
              }
            }
            
            GET my-index-000001/_search
            {
              "query": {
                "match": {
                  "tags": "elasticsearch" 
                }
              }
            }
            ```
            
    - Binary
        
        ### Binary
        
        - binary 유형은 이진 값을 Base64 인코딩 문자열로 허용한다. 기본적으로 저장되지 않으며 검색할 수 없다.
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "name": {
                    "type": "text"
                  },
                  "blob": {
                    "type": "binary"
                  }
                }
              }
            }
            
            PUT my-index-000001/_doc/1
            {
              "name": "Some binary blob",
              "blob": "U29tZSBiaW5hcnkgYmxvYg==" 
            }
            ```
            
            ### Parameters for binary fields
            
            - doc_values : sorting, aggregations, scripting에 사용할 수 있도록 필드를 column-stride 방식으로 디스크에 저장해야 할 때 true로 설정한다. 기본 값은 false
            - store : 필드 값을 _source 필드와 별도로 저장하고 검색할 수 있을지 여부. 기본 값은 false.
    - Boolean
        
        ### Boolean
        
        - boolean필드는 JSON true 및 false 값을 허용하지만 treu 또는 false로 해석되는 문자열도 허용할 수 있다.
        - terms aggreagation 같은 집계는 키에 1과 0을 사용하고 key_as_string에 문자열 “true”와 “false”를 사용한다. 스크립트에서 사용되는 boolean 필드는 true 및 flase를 반환한다.
            
            ```json
            POST my-index-000001/_doc/1?refresh
            {
              "is_published": true
            }
            
            POST my-index-000001/_doc/2?refresh
            {
              "is_published": false
            }
            
            GET my-index-000001/_search
            {
              "aggs": {
                "publish_state": {
                  "terms": {
                    "field": "is_published"
                  }
                }
              },
              "sort": [ "is_published" ],
              "fields": [
                {"field": "weight"}
              ],
              "runtime_mappings": {
                "weight": {
                  "type": "long",
                  "script": "emit(doc['is_published'].value ? 10 : 0)"
                }
              }
            }
            ```
            
            ### Parameters for boolean fields
            
            - doc_values : sorting, aggregations, scripting에 사용할 수 있도록 필드를 column-stride 방식으로 디스크에 저장할 지 확인. 기본 값은 false
            - index : 기본 값 true. doc_values만 활성화된 필드는 더 느리지만 용어 또는 범위 기반 쿼리를 사용하여 계속 쿼리할 수 있다.
            - ignore_malformed : 잘못된 데이터 유형을 필드에 indexing하려고 하면 기본적으로 예외가 발생하고 전체 문서가 거부된다. true로 설정할 경우 예외를 무시할 수 있다. 잘못된 형식의 필드는 indexing되지 않지만 다른 필드는 정상적으로 처리된다. script 매개변수를 사용하는 경우 설정할 수 없다.
            - null_value : true 또는 false 값을 허용한다. 이 값은 명시적 null 값으로 대체된다.  기본 값은 null로, 필드가 누락된 것으로 처리됨을 의미한다. script 매개변수를 사용하는 경우 설정할 수 없다.
            - on_script_error : script 매개변수로 정의된 스크립트가 indexing 시간에 오류를 발생 시키는 경우 수행할 작업을 정의한다. Accepts fail(default)는 전체 문서를 거부하게 하고, continue는 문서의 _ignored metadata 필드에 필드를 등록하고 indexing을 계속한다. script 매개변수를 사용하는 경우에만 설정 가능
            - script : script 매개변수 설정 시 필드는 소스에서 직접 값을 읽는 대신 스크립트에서 생성된 값을 indexing한다. 입력 문서에서 이 필드에 값을 설정하면 문서가 오류와 함께 거부된다. 스크립트는 runtime equivalent와 같은 형식이다.
            - store : 필드 값을 _source 필드와 별도로 저장하고 검색할 수 있는 지 여부. 기본 값은 false
            - meta : 필드에 대한 metadata
            
            ### Synthetic _source
            
            <aside>
            💡 Synthetic _source는 일반적으로 TSDB 인덱스(index.mode가 time_series로 설정된 index)에 대해서만 사용 가능하다. preview 기술로 제거될 수 있다.
            
            </aside>
            
            - boolean 필드는 기본 구성에서 Synthetic _source를 지원한다. Synthetic _source는 copy_to 또는 doc_values 비활성화와 함께 사용할 수 없다.
            - Synthetic _source는 항상 boolean 필드를 정렬한다.
                
                ```json
                PUT idx
                {
                  "mappings": {
                    "_source": { "mode": "synthetic" },
                    "properties": {
                      "bool": { "type": "boolean" }
                    }
                  }
                }
                PUT idx/_doc/1
                {
                  "bool": [true, false, true, false]
                }
                ```
                
            - 반환 값 :
                
                ```json
                {
                  "bool": [false, false, true, true]
                }
                ```
                
    - Completion
        
        ### Completion field type
        
        - completion suggester를 사용하려면 제안을 생성하려는 필드를 completion 유형으로 mapping한다. 이는 빠른 완료를 위해 필드 값을 indexing한다.
            
            ```json
            PUT music
            {
              "mappings": {
                "properties": {
                  "suggest": {
                    "type": "completion"
                  }
                }
              }
            }
            ```
            
            ### Parameters for completion fields
            
            - analyzer : 사용할 인덱스 분석기, 기본 값은 simple
            - search_analyzer : 사용할 검색 분석기, 기본 값은 analyzer
            - preserve_separators : 구분 기호 유지. 기본 값은 true. 비활성화된 경우 foof를 suggest 할 경우 Foo Fighters로 시작하는 필드를 찾을 수 있다.
            - preserve_position_increments : position increments를 활성화하며 기본 값은 true. 비 활성화되고 불용어 분석기를 사용하는 경우 b를 제안하여 The Beatles로 시작하는 필드를 얻을 수 있다.
            - max_input_length : 단일 입력의 길이를 제안하며 기본값은 50 UTF-16 코드 포인트.
    - Date
        
        ### Date
        
        - Elasticsearch 내부적으로 날짜는 UTC(시간대가 지정된 경우)로 반환 되고 epoch 이후 밀리 초를 나타내는 긴 숫자로 저장된다.
        - nanosecond의 정확도가 필요한 경우에는 date_nanos 필드 유형을 사용할 수 있다.
        - 날짜에 대한 쿼리는  긴 표현에 대한 범위 쿼리로 내부적으로 변환되며 aggregations 및 stored 필드의 결과는 필드와 연결된 날짜 형식에 따라 다시 문자열로 변환된다.
        - 날짜는 처음에 JSON 문서에서 long으로 제공되더라도 항상 문자열로 rendering된다.
        - 날짜 형식은 사용자 정의할 수 있지만 형식을 지정하지 않으면 기본 값을 사용한다.
            
            ```json
            "strict_date_optional_time||epoch_millis"
            ```
            
        - strict_date_optional_time 또는 milliseconds-since-the-epoch에서 지원하는 형식을 준수하는 선택적 timestamp가 있는 날짜를 허용한다.
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "date": {
                    "type": "date" 
                  }
                }
              }
            }
            
            PUT my-index-000001/_doc/1
            { "date": "2015-01-01" } 
            
            PUT my-index-000001/_doc/2
            { "date": "2015-01-01T12:10:30Z" } 
            
            PUT my-index-000001/_doc/3
            { "date": 1420070400001 } 
            
            GET my-index-000001/_search
            {
              "sort": { "date": "asc"} 
            }
            ```
            
            ### Multiple date formats
            
            - || 구분 기호로 구분하여 여러 형식을 지정할 수 있다. 일치하는 형식을 찾을 때까지 각 형식을 차례로 시도하며, 첫 번째 형식은 epoch 이후 밀리초 값을 다시 문자열로 변환하는 데 사용된다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "properties": {
                      "date": {
                        "type":   "date",
                        "format": "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd||epoch_millis"
                      }
                    }
                  }
                }
                ```
                
            
            ### Parameters for date fields
            
            - doc_values : 나중에 sorting, aggregation, scripting에 사용할 수 있도록 필드를 column-stride 방식으로 디스크에 저장할지 여부. 기본 값 false
            - format : 구문 분석할 수 있는 날짜 형식. 기본 값은 strict_data_optional_time || epoch_millis
            - locale : 월 이후 날짜를 구문 분석할 때 사용할 locale은 모든 언어에서 동일한 이름 and/or 약어를 가지지 않는다. 기본 값은 ROOT Locale
            - ignore_malformed : true인 경우 형식이 잘못된 숫자는 무시된다. 형식이 잘못된 숫자는 예외를 발생 시키고 전체 문서를 거부한다. script 매개변수를 사용할 경우 설정할 수 없다. 기본 값은 false
            - index : 기본 값 true. false일 경우 활성화된 날짜 필드만 doc_values 쿼리할 수 있지만 속도는 느리다.
            - null_value : 구성된 format 중 하나의 날짜 값을 명시적 null값으로 대체하는 필드로 허용한다. 기본 값은 null로, 필드가 누락된 것으로 처리된다. script 매개변수 사용 시 설정할 수 없다.
            - on_script_error : script매개변수로 정의된 스크립트가 indexing 시간에 오류를 발생시키는 경우 수행할 작업을 정의한다. Fail(기본 값)로 설정할 경우 전체 문서를 거부하게 되고, continue는 문서의 _ignored metadata 필드에 필드를 등록하고 indexing을 계속한다. 이 매개변수는 script 필드도 설정된 경우에만 설정할 수 있다.
            - script : 이 매개변수가 설정되면 필드는 소스에서 직접 값을 읽는 대신 이 script에서 생성된 값을 indexing한다. 입력 문서에서 이 필드에 값을 설정하면 문서가 오류와 함께 거부된다. script는 해당 runtime과 동일한 형식이며 긴 값의 timestamp를 내보내야 한다.
            - store : 필드 값을 _scource 필드와 별도로 저장하고 검색할 수 있는지 여부. 기본 값 false
            - meta : 필드에 대한 metadata
            
            ### Epoch seconds
            
            - 날짜를 seconds-since-the-epoch로 내보내야 하는 경우 형식이 sepoch_seond를 나열하는지 확인해야 한다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "properties": {
                      "date": {
                        "type":   "date",
                        "format": "strict_date_optional_time||epoch_second"
                      }
                    }
                  }
                }
                
                PUT my-index-000001/_doc/example?refresh
                { "date": 1618321898 }
                
                POST my-index-000001/_search
                {
                  "fields": [ {"field": "date"}],
                  "_source": false
                }
                ```
                
            - 반환 값은 다음과 같다.
                
                ```json
                {
                  "hits": {
                    "hits": [
                      {
                        "_id": "example",
                        "_index": "my-index-000001",
                        "_score": 1.0,
                        "fields": {
                          "date": ["2021-04-13T13:51:38.000Z"]
                        }
                      }
                    ]
                  }
                }
                ```
                
            
            ### Synthetic _source
            
            <aside>
            💡 Synthetic _source는 일반적으로 TSDB 인덱스(index.mode가 time_series로 설정된 index)에 대해서만 사용 가능하다. preview 기술로 제거될 수 있다.
            
            </aside>
            
            - data 필드는 기본 구성에서 Synthetic_source를 지원한다. _source 합성은 copy_to, ignore_malformed, doc_values와 함께 사용할 수 없다.
            - Synthetic_source는 항상 data 필드를 정렬한다.
    - Date nanoseconds
        
        ### Data nanoseonds field type
        
        - 기본 data 유형에 추가된 것. 기본 data 유형은 날짜를 밀리초 단위로 저장하지만, data_nanos 유형은 날짜를 나노초 단위로 저장하며 날짜 범위를 대략 1970년에서 2262년으로 제한한다. 날짜는 여전히 long 타입으로 저장되기 때문
        - 나노초에 대한 쿼리는 긴 표현에 대한 범위 쿼리로 내부적으로 변환되며 집계 및 저장된 필드의 결과는 필드와 연결된 날짜 형식에 따라 다시 문자열로 변환된다.
        - 날짜 형식은 사용자 정의로 할 수도 있고, no format을 지정하여 기본 값을 사용할 수 있다.
            
            ```json
            "strict_date_optional_time_nanos||epoch_millis"
            ```
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "date": {
                    "type": "date_nanos" 
                  }
                }
              }
            }
            
            PUT my-index-000001/_bulk?refresh
            { "index" : { "_id" : "1" } }
            { "date": "2015-01-01" } 
            { "index" : { "_id" : "2" } }
            { "date": "2015-01-01T12:10:30.123456789Z" } 
            { "index" : { "_id" : "3" } }
            { "date": 1420070400000 } 
            
            GET my-index-000001/_search
            {
              "sort": { "date": "asc"}, 
              "runtime_mappings": {
                "date_has_nanos": {
                  "type": "boolean",
                  "script": "emit(doc['date'].value.nano != 0)" 
                }
              },
              "fields": [
                {
                  "field": "date",
                  "format": "strict_date_optional_time_nanos" 
                },
                {
                  "field": "date_has_nanos"
                }
              ]
            }
            ```
            
        - data_nanos는 일부 경우 [링크](https://github.com/elastic/elasticsearch/issues/70085) 해당 날짜의 정확도가 떨어지므로 피해야 한다.
            
            ### Limitations
            
            - date_nanos 필드를 사용하는 경우에도 aggregation은 여전히 밀리초 단위이다. 이 제한은 transforms에도 영향을 미친다.
            
            ### Synthetic _source
            
            <aside>
            💡 Synthetic _source는 일반적으로 TSDB 인덱스(index.mode가 time_series로 설정된 index)에 대해서만 사용 가능하다. preview 기술로 제거될 수 있다.
            
            </aside>
            
            - date_nanos 필드는 기본 구성에서 Synthetic_source를 지원한다. Synthetic_source는 copy_to, ignore_malfored를 true로 설정하거나 doc_values를 비 활성화한 상태에서 같이 사용할 수 없다.
                
                ```json
                PUT idx
                {
                  "mappings": {
                    "_source": { "mode": "synthetic" },
                    "properties": {
                      "date": { "type": "date_nanos" }
                	    }
                  }
                }
                PUT idx/_doc/1
                {
                  "date": ["2015-01-01T12:10:30.000Z", "2014-01-01T12:10:30.000Z"]
                }
                ```
                
            - 반환 값
                
                ```json
                {
                  "date": ["2014-01-01T12:10:30.000Z", "2015-01-01T12:10:30.000Z"]
                }
                ```
                
    - Dense vector
        
        ### Dense vector field type
        
        - dense_vctor 필드 유형은 숫자 값의 dense vector를 지원한다.  dense vector는 주로 kNN(k_nearest neighbor) 검색에 사용된다.
        - dense-vector 유형은 집계 또는 정렬을 지원하지 않는다.
        - 기본적으로 float를 사용하여 element_type을 기반으로 하는 숫자 값의 배열로 dense_vector 필드를 추가한다.
            
            ```json
            PUT my-index
            {
              "mappings": {
                "properties": {
                  "my_vector": {
                    "type": "dense_vector",
                    "dims": 3
                  },
                  "my_text" : {
                    "type" : "keyword"
                  }
                }
              }
            }
            
            PUT my-index/_doc/1
            {
              "my_text" : "text1",
              "my_vector" : [0.5, 10, 6]
            }
            
            PUT my-index/_doc/2
            {
              "my_text" : "text2",
              "my_vector" : [-0.5, 10, 10]
            }
            ```
            
        - dense_vector는 항상 단일 값을 가진다. 하나의 dense_vctor 필드에 여러 값을 저장할 수 없다.
            
            ### Index vectors for kNN search
            
            - kNN 검색은 similarity metric으로 측정된 쿼리 vector에 가장 가까운 k 개의 vector를 찾는다.
            - dense vector 필드는 script_score 쿼리에서 문서의 순위를 지정하는데 사용할 수 있다. 이를 통해 모든 문서를 스캔하고 유사성에 따라 순위를 매겨 무차별 대입 kNN 검색을 수행할 수 있다.
            - 많은 경우 무차별 대입 kNN 검색은 충분히 효율적이지 않으며, 이를 이유로 dense_vector 유형은 vecotr를 특수 데이터 구조로 indexing하여 검색 API의 knn 옵션을 통해 빠른 kNN 검색을 지원한다.
            - 대략적인 kNN 검색을 위한 indexing vecotr는 비용이 많이 드는 프로세스다. 활성화된 vector 필드가 포함된 문서를 수집하는 데 상당한 시간이 걸릴 수 있다.
            - index 매개변수를 설정하여 indexing을 활성화할 수 있다.
                
                ```json
                PUT my-index-2
                {
                  "mappings": {
                    "properties": {
                      "my_vector": {
                        "type": "dense_vector",
                        "dims": 3,
                        "index": true,
                        "similarity": "dot_product" 
                      }
                    }
                  }
                }
                ```
                
            - Elasticsearch는 [HNSW 알고리즘](https://arxiv.org/abs/1603.09320) 을 사용하여 효율적인 kNN 검색을 지원한다. 대부분의 kNN 알고리즘과 마찬가지로 HNSW는 속도 향상을 위해 결과 정확도를 희생하는 근사 방법이다
            - dense vector 필드는 nested 매핑 내에 있는 경우 indexing할 수 없다.
            
            ### Parameters for dense vector fields
            
            - element_type : 선택 사항, string
                - Vector를 인코딩하는 데 사용되는 데이터 유형
                - 지원되는 데이터 유형은 flat(기본 값) 및 byte.
                - float 차원당 4바이트 부동 소수점 값을 indexing 한다.
                - byte 차원당 1바이트 정수 값을 indexing한다.
                - byte를 사용하면 정밀도가 낮아지는 대신 indexing 크기가 상당히 작아질 수 있다.
                - byte 를 사용하는 Vector에는 indexing과 검색을 모두 포함하여 -128에서 127 사이의 정수 값이 있는 차원이 필요하다.
            - dims : 필수 값, Integer
                - vector 차원의 수. indexing된 벡터(”index”: true) 의 경우 1024, indexing되지 않은 vector의 경우 2048을 초과할 수 없다.
                - indexed vectors의 차원 수는 2048까지 확장될 수 있다.
            - index : 선택 사항, boolean
                - true인 경우 kNN 검색 API를 사용하여 이 필드를 검색할 수 있다. 기본 값은 false.
            - similarity : 필수 값, string
                - kNN 검색에 사용할  vector similarity metric. 문서는 쿼리 벡터에 대한 벡터 필드의 유사성에 따라 순위가 매겨진다. 각 문서의 _score는 점수가 긍정적이고 더 큰 점수가 더 높은 순위에 해당하는지 확인하는 방식으로 유사성에서 파생된다.
                - index가 true면 이 매개변수는 필수가 된다.
                - Valid values for similarity
                    - l2_norm
                        - vector 간의 L²거리(Euclidean distance)를 기반으로 similarity 계산.  문서_score 는 1 / (1 + l2_norm(query, vector)^2)로 계산된다.
                    - dot_product
                        - 두 vector의 내적을 계산한다. 이 옵션은 코사인 similarity를 수행하는 최적화된 방법을 제공한다. 제약 조건 및 계산된 점수는 element_type에 의해 정의된다.
                        - element_type이 flaot이면 문서 및 쿼리 vector를 포함하여 모든 vectors는 단위 길이어야 한다. 문서 _score는 ( 1 + dot_product(query, vector)) / 2로 계산된다.
                        - element_type이 byte면 문서 및 쿼리 vector를 포함하여 모든 vector의 길이가 동일해야 한다. 그렇지 않으면 결과가 부정확해지며, 문서_score는 0.5 + (dot_product(query, vector) / (32768 * dims)). dims는 vector당 차원 수.
                    - consine
                        - 코사인 similarity를 계산한다. 코사인 similarity를 수행하는 가장 효율적인 방법은 모든 vector를 단위 길이로 정규화하고 dot_product를 사용하는 것.
                        - 원래 vector를 보존해야 하고 미리 정규화할 수 없을 경우에만 코사인을 사용해야 한다.
                        - 문서 _score는 (1 + cosine(query, vector) )/2로 계산된다.
                        - 이 경우 코사인이 정의되지 않았기에 코사인 similarity은 크기가 0인 vector를 허용하지 않는다.
            - index_options : 선택 사항, object
                - kNN indexing 알고리즘을 구성하는 선택적 section. HNSW 알고리즘에는 데이터 구조가 구축되는 방식에 영향을 미치는 두 개의 매개변수가 있다.
                - indexing 속도가 느려지는 대신 결과의 정확도를 개선하기 위해 조정할 수 있다.
                - index_options가 제공 되면 모든 속성을 정의해야 한다.
                - Properties of index_options
                    - type : 필수 값, string
                        - 사용할 kNN 알고리즘의 유형. 8.9 기준 hnsw만 지원된다.
                    - m : 필수 값, Integer
                        - HNSW 그래프에서 각 노드가 연결될 이웃 수. 기본 값은 16
                    - ef_construction : 필수 값, Integer
                        - 각각의 새 노드에 대해 가장 가까운 이웃 목록을 모으는 동안 추적할 후보 수. 기본 값은 100
            
            ### Synthetic _source
            
            <aside>
            💡 Synthetic _source는 일반적으로 TSDB 인덱스(index.mode가 time_series로 설정된 index)에 대해서만 사용 가능하다. preview 기술로 제거될 수 있다.
            
            </aside>
            
            - dense_vector 필드는 synthetic _source를 지원한다.
    - Flattened
        
        ### Flattened field type
        
        - 기본적으로 개체의 각 하위 필드는 별도로 mapping되고 indexing된다. 하위 필드의 이름이나 유형을 미리 알 수 없는 경우 동적으로 mapping된다.
        - flattened 형식은 전체 개체가 단일 필드로 mapping되는 대체 접근 방식을 제공한다. 객체가 주어지면 flattened mapping은 leaf 값을 구문 분석하고 keyword로 하나의 필드에 indexing한다. 그 뒤 간단한 쿼리 및 집계를 통해 개체의 콘텐츠를 검색할 수 있다.
        - flattened 데이터 유형은 고유 키 수가 많거나 알 수 없는 개체를 indexing하는 데 유용하다. 전체 JSON 개체에 대해 하나의 필드 mapping만 생성되므로 mapping explosion로 인해 고유한 필드 mapping이 너무 많이 발생하지 않도록 방지할 수 있다.
        - flattened object fields는 숫자 범위 쿼리 또는 강조 표시를 지원하지 않고 기본 쿼리만 허용된다.
        - flattened mapping 유형은 모든 값을 keyword로 취급하고 전체 검색 기능을 제공하지 않으므로 모든 문서 콘텐츠를 indexing하는 데 사용하면 안 된다. 각 하위 필드가 mapping에 고유한 항목이 있는 기본 접근 방식은 대부분의 경우 잘 작동한다.
        - 만드는 방법은 다음과 같다.
            
            ```json
            PUT bug_reports
            {
              "mappings": {
                "properties": {
                  "title": {
                    "type": "text"
                  },
                  "labels": {
                    "type": "flattened"
                  }
                }
              }
            }
            
            POST bug_reports/_doc/1
            {
              "title": "Results are not sorted correctly.",
              "labels": {
                "priority": "urgent",
                "release": ["v1.2.5", "v1.3.0"],
                "timestamp": {
                  "created": 1541458026,
                  "closed": 1541457010
                }
              }
            }
            ```
            
        - indexing 중에 JSON 개체의 각 leaf 값에 대해 토큰이 생성된다. 값은 분석이나 숫자 또는 날짜에 대한 특수 처리 없이 문자열 키워드로 indexing된다.
        - 최상위 flattened 필드를 쿼리하면 개체의 모든 leaf 값이 검색된다.
            
            ```json
            POST bug_reports/_search
            {
              "query": {
                "term": {"labels": "urgent"}
              }
            }
            ```
            
        - flattened 개체의 특정 키를 쿼리하기 위해 개체 점 표기법이 사용된다.
            
            ```json
            POST bug_reports/_search
            {
              "query": {
                "term": {"labels.release": "v1.3.0"}
              }
            }
            ```
            
            ### Supported operations
            
            - 값이 indexing 되는 방식에 대한 유사성으로 flattened 필드는 keyword 필드와 동일한 mapping 및 검색 기능을 대부분 공유한다.
            - 8.9 버전 기준 flattened object fields는 다음 쿼리 유형과 함께 사용 가능하다.
                - term, terms, terms_set
                - prefix
                - range
                - match, multi_match
                - query_string, simple_query_string
                - exists
            - 쿼리할 때 wildcard를 사용하여 필드 키를 참조할 수 없다.
            - range를 포함한 모든 쿼리는 값을 문자열 keyword로 취급한다.
            - flattened 필드에서는 강조 표시가 지원되지 않는다.
            - falttened object field를 기준으로 정렬 가능하며, terms와 같은 간단한 키워드 스타일 집계를 수행할 수 있다.
            - 숫자에 대한 특별한 지원은 없다.
            - JSON 객체의 모든 값은 keyword로 취급된다.
            - flattened object fields는 저장될 수 없다.
            - store mapping에서 매개변수를 지정할 수 없다.
            
            ### Retrieving flattened fields
            
            - 필드 값과 구체적인 하위 필드는 fields 매개변수를 사용하여 검색할 수 있다.
            - flattened 응답에는 _source의 변경되지 않은 구조가 포함된다.
            - 단일 하위 필드는 요청에 명시적으로 지정하여 가져올 수 있다. 구체적인 경로에서만 작동하며, wildcard는 사용하지 않는다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "properties": {
                      "flattened_field": {
                        "type": "flattened"
                      }
                    }
                  }
                }
                
                PUT my-index-000001/_doc/1?refresh=true
                {
                  "flattened_field" : {
                    "subfield" : "value"
                  }
                }
                
                POST my-index-000001/_search
                {
                  "fields": ["flattened_field.subfield"],
                  "_source": false
                }
                ```
                
            - 반환 값
                
                ```json
                {
                  "took": 2,
                  "timed_out": false,
                  "_shards": {
                    "total": 1,
                    "successful": 1,
                    "skipped": 0,
                    "failed": 0
                  },
                  "hits": {
                    "total": {
                      "value": 1,
                      "relation": "eq"
                    },
                    "max_score": 1.0,
                    "hits": [{
                      "_index": "my-index-000001",
                      "_id": "1",
                      "_score": 1.0,
                      "fields": {
                        "flattened_field.subfield" : [ "value" ]
                      }
                    }]
                  }
                }
                ```
                
            - Painless script를 사용하여 flattened field의 하위 필드에서 값을 검색할 수 있다.
                - doc[’<field_name>.<sub-field_name>’].value 를 사용
            
            ### Parameters for flattened object fields
            
            - depth_limit
                - 중첩된 내부 개체 측면에서 flattened object field의 최대 허용 깊이. faltted object filed가 제한 초과 시 오류 발생. 기본 값은 20. depth_limit은 update mapping API를 통해 동적으로 업데이트 가능
            - doc_values
                - 나중에 sorting, aggregation, scripting에 사용할 수 있도록 필드를 colum_stride 방식으로 디스크에 저장할 지 여부. 기본 값은 false
            - eager_global_ordinals
                - global ordinals를 새로 고칠 때 즉시 로드 해야 하는지 여부. 기본 값은 false
            - ignore_above
                - ignore_above 보다 긴 leaf 값은 indexing되지 않는다. 기본적으로 제한이 없으며 모든 값이 indexing된다. 제한은 전체 필드의 길이가 아니라 flattened object field 내의 leaf 값에 적용
            - index
                - 필드를 검색할 수 있는지 여부를 결정. 기본 값은 false
            - index_options
                - 기본 값은 docs이지만 freqs로 설정하여 scoring할 때 용어 빈도를 고려하도록 할 수 있다.
            - null_value
                - flattened object field 내의 명시적 null 값을 대체하는 문자열 값. 기본 값은 null
            - similarity
                - 어떤 scoring 알고리즘 또는 similarity를 사용해야 하는지 결정한다. 기본 값은 BM25
            - split_queries_on_whitespace
                - 이 필드에 대한 쿼리를 작성할 때 전체 텍스트 쿼리가 공백에 대한 입력을 분할해야 하는지 여부. 기본 값은 false
            - time_series_dimensions
                - 선택 사항, array of strings
                - flattened object field 내부의 필드 목록. 여기서 각 필드는 time series의 차원이다. 각 필드는 루트 필드의 상대 경로를 사용하여 지정되며 루트 필드 이름은 포함되지 않는다.
            
            ### Synthetic _source
            
            <aside>
            💡 Synthetic _source는 일반적으로 TSDB 인덱스(index.mode가 time_series로 설정된 index)에 대해서만 사용 가능하다. preview 기술로 제거될 수 있다.
            
            </aside>
            
            - flattened 필드는 기본 구성에서 synthetic _source를 지원한다. synthetic _source는 doc_values가 비활성된 상태에서 사용할 수 없다.
            - Synthetic _source는 항상 사전순으로 정렬하고 falttened 필드를 중복 제거한다.
            - Synthetic _source는 항상 개체 배열 대신 nested 개체를 사용한다.
            - Synthetic _source는 항상 단일 요소 배열에 대해 단일 값 필드를 사용한다.
    - Geopoint
        
        ### Geopoint field type
        
        - geo_point 유형의 필드는 다음과 같이 사용할 수 있는 위도-경도 쌍을 허용한다.
            - bounding box 내, 중심점의 특정 거리 내 또는 geo_shape 쿼리 내 geopoint 탐색
            - 중심점으로부터의 거리에 따라 문서 집계
            - 지리적 그리드를 기준으로 문서를 집계할 때는 geo_hash, geo_title, geo_hex 사용
            - metric 집계 geo_line을 사용하여 geopoint를 트랙으로 집계
            - 문서의 관련성 점수에 거리를 통합
            - 거리를 기준으로 문서를 정렬
        - geo_shape 및 point와 마찬가지로 geo_point는 GeoJSON 및 Well-Known Text 형식으로 지정 가능하다.
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "location": {
                    "type": "geo_point"
                  }
                }
              }
            }
            
            PUT my-index-000001/_doc/1
            {
              "text": "Geopoint as an object using GeoJSON format",
              "location": { 
                "type": "Point",
                "coordinates": [-71.34, 41.12]
              }
            }
            
            PUT my-index-000001/_doc/2
            {
              "text": "Geopoint as a WKT POINT primitive",
              "location" : "POINT (-71.34 41.12)" 
            }
            
            PUT my-index-000001/_doc/3
            {
              "text": "Geopoint as an object with 'lat' and 'lon' keys",
              "location": { 
                "lat": 41.12,
                "lon": -71.34
              }
            }
            
            PUT my-index-000001/_doc/4
            {
              "text": "Geopoint as an array",
              "location": [ -71.34, 41.12 ] 
            }
            
            PUT my-index-000001/_doc/5
            {
              "text": "Geopoint as a string",
              "location": "41.12,-71.34" 
            }
            
            PUT my-index-000001/_doc/6
            {
              "text": "Geopoint as a geohash",
              "location": "drm3btev3e86" 
            }
            
            GET my-index-000001/_search
            {
              "query": {
                "geo_bounding_box": { 
                  "location": {
                    "top_left": {
                      "lat": 42,
                      "lon": -72
                    },
                    "bottom_right": {
                      "lat": 40,
                      "lon": -74
                    }
                  }
                }
              }
            }
            ```
            
            ### Parameters for geo_point fields
            
            - ignore_malformed
                - true일 경우 형식이 잘못된 geopoint 무시. 기본 값 false인 경우 잘못된 형식의 geopoint는 예외를 발생 시키고 전체 문서를 거부한다.
                - 위도가 -90 ~ 90 범위를 벗어나거나 경도가 -180 ~ 180 범위를 벗어나면 geopoint가 잘못된 것으로 간주한다.
                - script 매개변수와 같이 사용할 수 없다.
            - ignore_z_value
                - 기본 값 true인 경우 3개의 차원 점(dimension points)이 허용되지만 (소스에 저장) 위도 및 경도 값만 indexing 된다. 3번째 차원은 무시된다.
                - false인 경우 위도 및 경도(2차원) 이상의 값을 포함하는 geopoint는 예외를 발생 시키고 전체 문서를 거부한다.
                - script 매개변수와 같이 사용할 수 없다.
            - index
                - 기본 값 true. doc_values만 활성화된 필드는 속도가 느리기는 하지만 계속 쿼리할 수 있다.
            - null_value
                - 명시적인 null 값을 대체하는 지리점 값을 허용한다. 기본 값은 null.
                - script 매개변수와 같이 사용할 수 없다.
            - on_script_error
                - script 매개변수로 정의된 스크립트가 indexing 시 오류를 발생시키는 경우 수행할 작업을 정의한다.
                - 기본 값인 false인 경우 전체 문서가 거부되며 문서의 _ignored metadata 필드에 해당 필드를 indexing한다.
                - script 필드가 설정된 경우에만 사용 가능하다.
            - script
                - 필드는 소스에서 직접 값을 읽는 대신 이 스크립트에서 생성된 값을 indexing한다.
            
            ### Using geopoints in scripts
            
            - 스크립트에서 geopoint 값에 액세스할 때 해당 값은 각각 .lat, .lon 값에 대한 액세스를 허용하는 GeoPoint 개체로 반환 된다.
                
                ```json
                def geopoint = doc['location'].value;
                def lat      = geopoint.lat;
                def lon      = geopoint.lon;
                ```
                
            - 성능상의 문제로 위도/경도 값에 직접 액세스하는 것을 권장한다.
                
                ```json
                def lat      = doc['location'].lat;
                def lon      = doc['location'].lon;
                ```
                
            
            ### Synthetic source
            
            <aside>
            💡 Synthetic _source는 일반적으로 TSDB 인덱스(index.mode가 time_series로 설정된 index)에 대해서만 사용 가능하다. preview 기술로 제거될 수 있다.
            
            </aside>
            
            - geo_point 필드는 기본 구성에서 Synthetic _source를 지원한다. Synthetic _source는 ignore_malformed, copy_to, doc_values 비활성화와 함께 사용할 수 없다.
            - Synthetic _source는 항상 geo_point 필드(먼저 위도, 그 다음 경도)를 정렬하고 저장된 정밀도로 줄인다.
    - Geoshape
        
        ### Geoshape field type
        
        - geo_shape 데이터 타입은 직사각형, 선, 다각형과 같은 임의의 지형 형태에 대한 indexing 및 검색을 용이하게 한다. indexing 되는 데이터에 점 이외의 모양이 포함되어 있는 경우 이 mapping을 사용해야 한다. 데이터에 점만 포함된 경우 geo_point 또는 geo_shape로 indexing할 수 있다.
        - geo_hex 그리드에 대한 그리드 집계는 geo_shape 필드에 대해 지원되지 않는다.
            
            ### Mapping Options
            
            - geo_shape mapping은 GeoJSON 또는 WKT 기하학 개체를 geo_shape 유형에 mapping한다. 이를 활성화하려면 사용자는 필드를 geo_shape 유형에 명시적으로 mapping해야 한다.
                
                
                | 옵션 | 설명 | 기본 값 |
                | --- | --- | --- |
                | orientation | 선택. 필드의 KWT 다각형에 대한 기본 방향 
                
                매개변수는 RIGHT 또는 LEFT 값만 설정하고 반환한다. 
                
                RIGHT는 right, counterclockwise, ccw 중 하나로
                
                LEFT는 left, clockwise, cw 중 하나로 설정 가능하다. | RIGHT |
                | ignore_malformed | true인 경우 잘못된 GeoJSON 또는 WKT 모양이 무시된다. 
                
                false 인 경우 잘못된 GeoJSON 또는 WKT 모양은 예외를 발생 시키고 전체 문서를 거부한다. | false |
                | ignore_z_value | true인 경우 3차원 점이 허용되지만 위도 및 경도 값만 indexing된다. 세 번째 차원은 무시된다. 
                
                false인 경우 위도 및 경도 값 이상을 포함하는 지리 좌표는 예외를 발생 시키고 전체 문서를 거부한다. | true |
                | coerce | true인 경우 다각형의 닫히지 않은 선형 링이 자동으로 닫힌다. | false |
                | index | true인 경우 활성화된 필드만 doc_values 쿼리할 수 있지만 속도는 더 느리다. | true |
                | doc_values | 추후 집계 및 scripting에 사용할 수 있도록 필드를 column-stride 방식으로 저장한다. | true |
            
            ### Indexing approach
            
            - Geoshape 유형은 모양을 삼각형 메시로 분해하고 각 삼각형을 BKD 트리의 7차원 지점으로 indexing하여 indexing된다. 이는 모든 공간 관계가 원래 모양의 인코딩된 벡터 표현을 사용하여 계산되므로 거의 완벽한 공간 해상도를 제공한다.
            - tessellator의 성능은 주로 다각형/다중 다각형을 정의하는 정점 수에 따라 달라진다.
                
                ```json
                PUT /example
                {
                  "mappings": {
                    "properties": {
                      "location": {
                        "type": "geo_shape"
                      }
                    }
                  }
                }
                ```
                
            
            ### Input Structure
            
            - 도형은 GeoJSON 또는 WKT 형식을 사용하여 표현할 수 있다.
                
                
                | GeoJSON 유형 | WKT 유형 | Elasticsearch 유형 | 설명 |
                | --- | --- | --- | --- |
                | Point | POINT | point | 단일 지리적 좌표 |
                | LineString | LINESTRING | linestring | 두 개 이상의 점이 주어진 임의의 선 |
                | Polyjon | POLYGON | polygon | 첫 번째 점과 마지막 점이 일치해야 하는 닫힌 가각형 |
                | MultiPoint | MULTIPOINT | multipoint | 연결되지는 않았지만 관련 가능성이 높은 지점의 배열 |
                | MultiLineString | MULTILINESTING | multilinestring | 별도의 linestring 배열 |
                | MultiPolygon | MULTIPOLYGON | multipolygon | 별도의 다각형 배열 |
                | GeometryCollection | GEOMETRYCOLLECTION | geometrycollection | 여러 유형이 공존할 수 있는, 도형과 유사한 GeoJSON 모형 |
                | N/A | BBOX | envelope | 왼쪽 상단과 오른쪽 하단 점만 지정하여 지정되는 경계 사각형 또는 봉투 |
                - 모든 유형에는 내부 필드 type과 coordinates 필드가 둘 다 필요하다.
                - 필드 목록
                    
                    ### Point
                    
                    - 포인트는 건물 위치나 스마트폰의 Geolocation API가 제공하는 현재 위치와 같은 단일 지리적 좌표이다.
                    - GeoJSON 예시
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : {
                            "type" : "Point",
                            "coordinates" : [-77.03653, 38.897676]
                          }
                        }
                        ```
                        
                    - WKT 예시
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : "POINT (-77.03653 38.897676)"
                        }
                        ```
                        
                    
                    ### LineString
                    
                    - 두 개 이상의 위치 배열로 정의된 linestring. 두 점만 지정하면 linestring이 직선을 나타낸다.
                    - 세 개 이상의 점을 지정하면 임의의 경로가 생성된다.
                    - GeoJSON 예시
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : {
                            "type" : "LineString",
                            "coordinates" : [[-77.03653, 38.897676], [-77.009051, 38.889939]]
                          }
                        }
                        ```
                        
                    - WKT 예시
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : "LINESTRING (-77.03653 38.897676, -77.009051 38.889939)"
                        }
                        ```
                        
                    
                    ### Polyjon
                    
                    - 다각형은 점 목록으로 정의된다. 각(외부) 목록의 첫 번째 및 마지막 점은 동일해야 한다.
                    - GeoJSON 예시
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : {
                            "type" : "Polygon",
                            "coordinates" : [
                              [ [100.0, 0.0], [101.0, 0.0], [101.0, 1.0], [100.0, 1.0], [100.0, 0.0] ]
                            ]
                          }
                        }
                        ```
                        
                    - 구멍이 있는 GeoJSON
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : {
                            "type" : "Polygon",
                            "coordinates" : [
                              [ [100.0, 0.0], [101.0, 0.0], [101.0, 1.0], [100.0, 1.0], [100.0, 0.0] ],
                              [ [100.2, 0.2], [100.8, 0.2], [100.8, 0.8], [100.2, 0.8], [100.2, 0.2] ]
                            ]
                          }
                        }
                        ```
                        
                    - WKT 예시
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : "POLYGON ((100.0 0.0, 101.0 0.0, 101.0 1.0, 100.0 1.0, 100.0 0.0))"
                        }
                        ```
                        
                    - 구멍이 있는 WKT
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : "POLYGON ((100.0 0.0, 101.0 0.0, 101.0 1.0, 100.0 1.0, 100.0 0.0), (100.2 0.2, 100.8 0.2, 100.8 0.8, 100.2 0.8, 100.2 0.2))"
                        }
                        ```
                        
                    - 다각형의 방향은 정점의 순서 RIGHT 또는 LEFT를 나타낸다.
                    - orientation mapping 매개변수를 사용하여 WKT 다각형의 기본 방향을 설정할 수 있다.
                    - GeoJSON 다각형은 방향 mapping 매개변수 값에 관계없이 기본 방향인 RIGHT를 사용한다.
                    - 문서 수준 방향 매개변수를 사용하여 GeoJSON 다각형의 기본 방향을 재정의할 수 있다.
                    - 문서 수준 방향 LEFT로 지정
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : {
                            "type" : "Polygon",
                            "orientation" : "LEFT",
                            "coordinates" : [
                              [ [-177.0, 10.0], [176.0, 15.0], [172.0, 0.0], [176.0, -15.0], [-177.0, -10.0], [-177.0, 10.0] ]
                            ]
                          }
                        }
                        ```
                        
                    
                    ### MultiPoint
                    
                    - GeoJSON 포인트 목록
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : {
                            "type" : "MultiPoint",
                            "coordinates" : [
                              [102.0, 2.0], [103.0, 2.0]
                            ]
                          }
                        }
                        ```
                        
                    - WKT 포인트 목록
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : "MULTIPOINT (102.0 2.0, 103.0 2.0)"
                        }
                        ```
                        
                    
                    ### MultiLineString
                    
                    - GeoJSON LineString 목록
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : {
                            "type" : "MultiLineString",
                            "coordinates" : [
                              [ [102.0, 2.0], [103.0, 2.0], [103.0, 3.0], [102.0, 3.0] ],
                              [ [100.0, 0.0], [101.0, 0.0], [101.0, 1.0], [100.0, 1.0] ],
                              [ [100.2, 0.2], [100.8, 0.2], [100.8, 0.8], [100.2, 0.8] ]
                            ]
                          }
                        }
                        ```
                        
                    - WKT LineString 목록
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : "MULTILINESTRING ((102.0 2.0, 103.0 2.0, 103.0 3.0, 102.0 3.0), (100.0 0.0, 101.0 0.0, 101.0 1.0, 100.0 1.0), (100.2 0.2, 100.8 0.2, 100.8 0.8, 100.2 0.8))"
                        }
                        ```
                        
                    
                    ### MultiPolygon
                    
                    - GeoJSON 다각형 목록
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : {
                            "type" : "MultiPolygon",
                            "coordinates" : [
                              [ [[102.0, 2.0], [103.0, 2.0], [103.0, 3.0], [102.0, 3.0], [102.0, 2.0]] ],
                              [ [[100.0, 0.0], [101.0, 0.0], [101.0, 1.0], [100.0, 1.0], [100.0, 0.0]],
                                [[100.2, 0.2], [100.8, 0.2], [100.8, 0.8], [100.2, 0.8], [100.2, 0.2]] ]
                            ]
                          }
                        }
                        ```
                        
                    - WKT 다각형 목록
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : "MULTIPOLYGON (((102.0 2.0, 103.0 2.0, 103.0 3.0, 102.0 3.0, 102.0 2.0)), ((100.0 0.0, 101.0 0.0, 101.0 1.0, 100.0 1.0, 100.0 0.0), (100.2 0.2, 100.8 0.2, 100.8 0.8, 100.2 0.8, 100.2 0.2)))"
                        }
                        ```
                        
                    
                    ### GeometryCollection
                    
                    - GeoJSON GeometryCollection
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : {
                            "type": "GeometryCollection",
                            "geometries": [
                              {
                                "type": "Point",
                                "coordinates": [100.0, 0.0]
                              },
                              {
                                "type": "LineString",
                                "coordinates": [ [101.0, 0.0], [102.0, 1.0] ]
                              }
                            ]
                          }
                        }
                        ```
                        
                    - WKT GeometryCollection
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : "GEOMETRYCOLLECTION (POINT (100.0 0.0), LINESTRING (101.0 0.0, 102.0 1.0))"
                        }
                        ```
                        
                    
                    ### Envelope
                    
                    - Elasticsearch는 [[minLon, maxLat], [maxLon, minLat]] 형식의 경계 직사각형을 표현하기 위해 모양의 왼쪽 상단과 오른쪽 하단 점에 대한 좌표로 구성된 봉투 Envelope 유형을 지원한다.
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : {
                            "type" : "envelope",
                            "coordinates" : [ [100.0, 1.0], [101.0, 0.0] ]
                          }
                        }
                        ```
                        
                        ```json
                        POST /example/_doc
                        {
                          "location" : "BBOX (100.0, 102.0, 2.0, 0.0)"
                        }
                        ```
                        
                    
                    ### Circle
                    
                    - GeoJSON과 WKT 모두 점 반경 원 유형을 지원하지 않는다.
                    - 대신 원 수집 프로세서를 사용하여 원을 다각형으로 근사화한다.
            
            ### Sorting and Retrieving Index Shapes
            
            - 복잡한 입력 구조와 도형의 인덱스 표현으로 인해 현재는 도형을 정렬하거나 해당 필드를 직접 검색하는 것이 불가능하다. geo_shape 값은 _source 필드를 통해서만 검색할 수 있다.
    - Histogram
        
        ### Histogram field type
        
        - 히스토그램을 나타내는 사전 집계된 숫자 데이터를 저장하는 필드다. 데이터는 두 쌍의 배열을 사용하여 정의된다.
            - values array of double numbers는 히스토그램의 버킷을 나타낸다. 오름차순으로 제공되어야 한다.
            - array of integer numbers는 각 버킷에 속하는 값 수를 나타낸다. 이 숫자는 양수이거나 0이어야 한다.
        - 각 배열의 요소는 개수 배열의 동일한 위치에 있는 요소에 해당하므로 이 두 배열의 길이는 동일해야 한다.
            
            <aside>
            💡 히스토그램 필드는 문서당 단일 값 쌍과 개수 배열만 저장할 수 있다. nested 배열은 지원되지 않는다. 
            
            히스토그램 필드는 정렬을 지원하지 않는다.
            
            </aside>
            
            ### Uses
            
            - histogram 필드는 주로 집계와 함께 사용한다.
            - 집계를 위해 더 쉽게 액세스할 수 있도록 histogram 필드 데이터는 indexing되지 않고 이진 문서 값으로 저장된다.
            - 바이트 단위의 크기는 최대 13 * numValues가 된다.
            - numValues는 제공된 배열의 길이.
            - 데이터는 indexing되지 않으므로 아래의 집계 및 쿼리에만 histogram 필드를 사용할 수 있다.
                - min aggregation
                - max aggregation
                - sum aggregation
                - value_count aggregation
                - avg aggregation
                - pernetiles aggregation
                - percentile ranks aggregation
                - boxplot aggregation
                - histogram aggregation
                - range aggregation
                - exists query
            
            ### Building a histogram
            
            - 히스토그램을 집계의 일부로 사용하는 경우 결과의 정확성은 히스토그램이 구성된 방식에 따라 달라진다. 구축하는 데 사용될 백분위수 집계 모드를 고려하는 것이 중요하다.
                - T-Digest 모드의 경우 values 배열은 평균 중심 위치를 나타내고 개수 배열은 각 중심에 속하는 값의 수를 나타낸다. 알고리즘이 이미 백분위수 근사치를 계산하기 시작한 경우 이 부정확성은 히스토그램에 그대로 유지된다.
                - HDR(High Dynamic Range) 히스토그램 모드의 경우 값 배열은 각 버킷 간격의 고정 상한을 나타내고 개수 배열은 각 간격에 해당하는 값 수를 나타낸다.
                    - HDR 구현은 고정된 최악의 경우 백분율 오류(유효 자릿수로 지정)를 유지하므로 히스토그램을 생성할 때 사용되는 값은 집계 시 달성할 수 있는 최대 정확도가 된다.
            - 히스토그램 필드는 알고리즘에 구애받지 않으며 T-Digest 또는 HDRHistogram에 특정한 데이터를 저장하지 않는다.
            - 이는 필드가 기술적으로 두 알고리즘 중 하나로 집계될 수 있음을 의미한다.
            
            ### Synthetic _source
            
            <aside>
            💡 Synthetic _source는 일반적으로 TSDB 인덱스(index.mode가 time_series로 설정된 index)에 대해서만 사용 가능하다. preview 기술로 제거될 수 있다.
            
            </aside>
            
            - 히스토그램 필드는 기본 구성에서 Synthetic _source를 지원한다. Synthetic _source는 ignore_malformed 또는 copy_to와 함께 사용할 수 없다.
            - 공간을 절약하기 위해 개수가 0인 버킷은 히스토그램 문서 값에 저장되지 않는다.
            - 결과적으로 합성 소스가 활성화된 인덱스에서 히스토그램 필드를 indexing할 때 개수가 0인 버킷을 포함하는 히스토그램을 indexing하면 히스토그램을 다시 가져올 때 버킷이 누락된다.
            
            ### Examples
            
            - 다음 create index API 요청은 두 개의 필드 mapping을 사용하여 새 인덱스를 생성한다.
                - my_histogram : 백분위수 데이터를 저장하는 데 사용되는 histogram 필드
                - my_text : 히스토그램 제목을 저장하는 데 사용되는 keyword 필드
                    
                    ```json
                    PUT my-index-000001
                    {
                      "mappings" : {
                        "properties" : {
                          "my_histogram" : {
                            "type" : "histogram"
                          },
                          "my_text" : {
                            "type" : "keyword"
                          }
                        }
                      }
                    }
                    ```
                    
            - 혹은, 아래와 같은 방식으로도 가능하다.
                
                ```json
                PUT my-index-000001/_doc/1
                {
                  "my_text" : "histogram_1",
                  "my_histogram" : {
                      "values" : [0.1, 0.2, 0.3, 0.4, 0.5], 
                      "counts" : [3, 7, 23, 12, 6] 
                   }
                }
                
                PUT my-index-000001/_doc/2
                {
                  "my_text" : "histogram_2",
                  "my_histogram" : {
                      "values" : [0.1, 0.25, 0.35, 0.4, 0.45, 0.5], 
                      "counts" : [8, 17, 8, 7, 6, 2] 
                   }
                }
                ```
                
    - IP
        
        ### IP field type
        
        - IPv4, IPv6 주소를 indexing/저장할 수 있다.
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "ip_addr": {
                    "type": "ip"
                  }
                }
              }
            }
            
            PUT my-index-000001/_doc/1
            {
              "ip_addr": "192.168.1.1"
            }
            
            GET my-index-000001/_search
            {
              "query": {
                "term": {
                  "ip_addr": "192.168.0.0/16"
                }
              }
            }
            ```
            
        - ip_range 데이터 유형을 사용하여 단일 필드에 IP 범위를 저장할 수도 있다.
            
            ### Parameters for ip fields
            
            - doc_values
                - 추후 sorting, aggregation, scripting에 사용할 수 있도록 필드를 column-stride 방식으로 디스크에 저장할 지 유무.
                - 기본 값은 true
            - ignore_malformed
                - true일 경우 잘못된 IP 주소 무시.
                - 기본 값인 false인 경우 잘못된 IP 주소는 예외를 발생 시키고 전체 문서를 거부한다.
                - script 매개변수와 함께 사용 불가능
            - index
                - 기본 값 true.
                - doc_values만 활성화된 필드는 속도가 느리지만 용어 또는 범위 기반 쿼리를 사용하여 쿼리 가능.
            - null_value
                - 명시적인 null 값을 대체하는 IPv4, IPv6 값을 허용한다.
                - 기본 값은 null.
                - script 매개변수와 함께 사용 불가능.
            - on_script_error
                - script 매개변수로 정의된 스크립트가 indexing 시 오류를 발생시키는 경우 수행할 작업을 정의.
                - 전체 문서를 거부하게 만드는 reject(default)를 수락하고 문서의 _ignored metadata 필드에 해당 필드를 등록하고 indexing을 계속하는 무시를 허용한다.
                - script 필드도 설정된 경우에만 설정 가능
            - script
                - 설정 시 필드는 소스에서 직접 값을 읽는 대신 이 스크립트에서 생성된 값을 indexing한다.
                - 입력 문서에서 이 필드에 값을 설정하면 문서가 오류와 함께 거부된다.
                - 스크립트는 해당 runtime과 동일한 형식이며 IPv4 또는 IPv6 형식의 주소가 포함된 문자열을 내보내야 한다.
            - store
                - 필드 값을 _source 필드와 별도로 저장하고 검색할 수 있는지 여부.
                - 기본 값은 false
            - time_series_dimension
                - 선택 사항, boolean
                - 필드를 time series dimension으로 표현한다.
                - 기본 값은 flase
                - 인덱스 index.mapping.dimension_fields.limit 설정은 인덱스의 차원 수를 제한한다.
                - doc_values 및 index mapping 매개변수는 true여야 한다.
                - 필드 값은 array나 multi-value일 수 없다.
            
            ### Querying ip fields
            
            - IP 주소를 쿼리하는 가장 일반적인 방법은 CIDR 표기법을 사용하는 것이다.
            - [ip_address]/[prefix_length]를 사용한다.
                
                ```json
                GET my-index-000001/_search
                {
                  "query": {
                    "term": {
                      "ip_addr": "192.168.0.0/16"
                    }
                  }
                }
                ```
                
                ```json
                GET my-index-000001/_search
                {
                  "query": {
                    "term": {
                      "ip_addr": "2001:db8::/48"
                    }
                  }
                }
                ```
                
            - 또한 콜론은 query_string 쿼리의 특수 문자이므로 IPv6 주소를 escape 해야 한다. 검색된 값을 따옴표로 묶어서 가능하다.
                
                ```json
                GET my-index-000001/_search
                {
                  "query": {
                    "query_string" : {
                      "query": "ip_addr:\"2001:db8::/48\""
                    }
                  }
                }
                ```
                
            
            ### Synthetic _source
            
            <aside>
            💡 Synthetic _source는 일반적으로 TSDB 인덱스(index.mode가 time_series로 설정된 index)에 대해서만 사용 가능하다. preview 기술로 제거될 수 있다.
            
            </aside>
            
            - ip 필드는 기본 구성에서 합성을 지원한다.
            - Synthetic _source는 copy_to 또는 doc_values가 비 활성화된 상태에서 함께 사용할 수 없다.
            - Synthetic _source는 항상 IP 필드를 정렬하고 중복 항목을 제거한다.
    - Join
        
        ### Join field type
        
        - join 데이터 유형은 동일한 인덱스의 문서 내에서 상위/하위 관계를 생성하는 특수 필드다.
        - relations section은 문서 내에서 가능한 관계 집합을 의미하며 각 관계는 부모 이름과 자식 이름이다.
            
            <aside>
            💡 relational model을 복제하기 위해 여러 수준의 관계를 사용하는 것은 권장되지 않는다. 관계의 각 수준은 쿼리 시 메모리 및 계산 측면에서 overhead를 추가한다. 더 나은 검색 성능을 위해서는 데이터를 비정규화하는 것이 권장된다.
            
            </aside>
            
        - 상위/하위 관계는 다음과 같이 정의된다.
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "my_id": {
                    "type": "keyword"
                  },
                  "my_join_field": { 
                    "type": "join",
                    "relations": {
                      "question": "answer" 
                    }
                  }
                }
              }
            }
            ```
            
        - join을 사용하여 문서를 indexing하려면 관계 이름과 문서의 선택적 부모를 source에 제공해야 한다.
            
            ```json
            PUT my-index-000001/_doc/1?refresh
            {
              "my_id": "1",
              "text": "This is a question",
              "my_join_field": {
                "name": "question" 
              }
            }
            
            PUT my-index-000001/_doc/2?refresh
            {
              "my_id": "2",
              "text": "This is another question",
              "my_join_field": {
                "name": "question"
              }
            }
            ```
            
        - 혹은 상위 문서 indexing 시 일반 객체 표기법으로 캡슐화하는 대신 관계 이름만 바로 가기로 지정하도록 선택할 수 있다.
            
            ```json
            PUT my-index-000001/_doc/1?refresh
            {
              "my_id": "1",
              "text": "This is a question",
              "my_join_field": "question" 
            }
            
            PUT my-index-000001/_doc/2?refresh
            {
              "my_id": "2",
              "text": "This is another question",
              "my_join_field": "question"
            }
            ```
            
        - 자식을 indexing할 때는 관계 이름과 문서의 부모 ID를 _source에 추가해야 한다.
            
            <aside>
            💡 동일한 shard에서 상위의 계보를 indexing해야 하므로 항상 더 큰 상위 ID를 사용하여 하위 문서를 routing해야 한다.
            
            </aside>
            
            ```json
            PUT my-index-000001/_doc/3?routing=1&refresh 
            {
              "my_id": "3",
              "text": "This is an answer",
              "my_join_field": {
                "name": "answer", 
                "parent": "1" 
              }
            }
            
            PUT my-index-000001/_doc/4?routing=1&refresh
            {
              "my_id": "4",
              "text": "This is another answer",
              "my_join_field": {
                "name": "answer",
                "parent": "1"
              }
            }
            ```
            
            ### Parent-join and performance
            
            - join 필드는 관계 데이터베이스의 join처럼 사용하면 안 된다.
            - Elasticsearch에서 우수한 성능의 핵심은 데이터를 문서로 비정규화하는 것이므로, 각 조인 필드 has_child 또는 has_parent 쿼리는 쿼리 성능에 상당한 요금을 추가한다.
            - 또한 global ordinals를 생성하도록 촉발할 수도 있다.
            - join 필드가 의미가 있는 유일한 경우는 데이터에 한 Entity가 다른 Entity보다 훨씬 많은 일대다 관계가 포함된 경우이다.
            
            ### Parent-join restrictions
            
            - 인덱스당 하나의 join 필드 매핑만 허용된다.
            - 상위 및 하위 문서는 동일한 shard에서 indexing되어야 한다. 하위 문서를 가져오거나 삭제 하거나, 업데이트 할 때 동일한 routing 값을 제공해야 한다.
            - 요소는 여러 자식을 가질 수 있지만 부모는 하나만 가질 수 있다.
            - 기존 join 필드에 새 관계를 추가할 수 있다.
            - 기존 요소에 자식을 추가하는 것도 가능하지만 해당 요소가 이미 부모인 경우에만 가능하다.
            
            ### Searching with parent-join
            
            - 상위 join은 문서 내의 관계 이름 (my_parent, my_child, …)을 indexing하기 위해 하나의 필드를 생성한다.
            - 또한 부모/자식 관계 당 하나의 필드를 생성한다. 이 필드의 이름은 join 필드의 이름 뒤에 #과 관계의 상위 이름이 온다.
            - join 필드에는 문서가 하위 문서인 경우 문서가 링크되는 상위 _id가, 상위 문서인 경우 문서의 _id가 포함된다.
            - join 필드가 포함된 index를 검색할 때 이 두 필드는 항상 검색에 반환 된다.
            - Request
                
                ```json
                GET my-index-000001/_search
                {
                  "query": {
                    "match_all": {}
                  },
                  "sort": ["my_id"]
                }
                ```
                
            - 응답
                
                ```json
                {
                  ...,
                  "hits": {
                    "total": {
                      "value": 4,
                      "relation": "eq"
                    },
                    "max_score": null,
                    "hits": [
                      {
                        "_index": "my-index-000001",
                        "_id": "1",
                        "_score": null,
                        "_source": {
                          "my_id": "1",
                          "text": "This is a question",
                          "my_join_field": "question"         
                        },
                        "sort": [
                          "1"
                        ]
                      },
                      {
                        "_index": "my-index-000001",
                        "_id": "2",
                        "_score": null,
                        "_source": {
                          "my_id": "2",
                          "text": "This is another question",
                          "my_join_field": "question"          
                        },
                        "sort": [
                          "2"
                        ]
                      },
                      {
                        "_index": "my-index-000001",
                        "_id": "3",
                        "_score": null,
                        "_routing": "1",
                        "_source": {
                          "my_id": "3",
                          "text": "This is an answer",
                          "my_join_field": {
                            "name": "answer",                 
                            "parent": "1"                     
                          }
                        },
                        "sort": [
                          "3"
                        ]
                      },
                      {
                        "_index": "my-index-000001",
                        "_id": "4",
                        "_score": null,
                        "_routing": "1",
                        "_source": {
                          "my_id": "4",
                          "text": "This is another answer",
                          "my_join_field": {
                            "name": "answer",
                            "parent": "1"
                          }
                        },
                        "sort": [
                          "4"
                        ]
                      }
                    ]
                  }
                }
                ```
                
            
            ### Parent-join queries and aggregations
            
            - join 필드 값은 aggregation 및 script에서 액세스할 수 있으며 다음 parent_id 쿼리로 쿼리할 수 있다.
                
                ```json
                GET my-index-000001/_search
                {
                  "query": {
                    "parent_id": { 
                      "type": "answer",
                      "id": "1"
                    }
                  },
                  "aggs": {
                    "parents": {
                      "terms": {
                        "field": "my_join_field#question", 
                        "size": 10
                      }
                    }
                  },
                  "runtime_mappings": {
                    "parent": {
                      "type": "long",
                      "script": """
                        emit(Integer.parseInt(doc['my_join_field#question'].value)) 
                      """
                    }
                  },
                  "fields": [
                    { "field": "parent" }
                  ]
                }
                ```
                
            
            ### Global ordinals
            
            - join 필드는 global ordinals를 사용하여 조인 속도를 높인다.
            - shard를 변경한 후에는 global ordinals를 다시 작성해야 한다.
            - 더 많은 상위 ID 값이 shard에 저장될 수록 join 필드의 global ordinals를 다시 build하는데 더 오래 걸린다.
            - global ordinals는 기본적으로 eagerly 하게 작성된다.
            - 인덱스가 변경된 경우 join 필드의 global ordinals가 새로 고침의 일부로 다시 작성된다.
            - join 필드가 자주 사용되지 않고 쓰기가 자주 발생하는 경우에는 eager loding을 비 활성화하는 것이 권장된다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "properties": {
                      "my_join_field": {
                        "type": "join",
                        "relations": {
                           "question": "answer"
                        },
                        "eager_global_ordinals": false
                      }
                    }
                  }
                }
                ```
                
            - global ordinals에서 사용하는 heap의 양은 다음과 같이 부모 관계 별로 확인 가능하다.
                
                ```json
                # Per-index
                GET _stats/fielddata?human&fields=my_join_field#question
                
                # Per-node per-index
                GET _nodes/stats/indices/fielddata?human&fields=my_join_field#question
                ```
                
            
            ### Multiple children per parent
            
            - 단일 parent에 대해 여러 children을 정의할 수 있다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "properties": {
                      "my_join_field": {
                        "type": "join",
                        "relations": {
                          "question": ["answer", "comment"]  
                        }
                      }
                    }
                  }
                }
                ```
                
            
            ### Multiple levels of parent join
            
            - 여러 수준의 parent/childer
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "properties": {
                      "my_join_field": {
                        "type": "join",
                        "relations": {
                          "question": ["answer", "comment"],  
                          "answer": "vote" 
                        }
                      }
                    }
                  }
                }
                ```
                
            - 손자 문서를 indexing 할 경우에는 조부모와 동일한 routing 값이 필요하다.
    - Keyword
        
        ### Keyword type family
        
        - keyword family에는 다음 필드 유형이 포함된다.
            - keyword
                - ID, 이메일 주소, 호스트 이름, 상태 코드, 우편 번호 또는 태그와 같은 구조화된 콘텐츠에 사용
            - constant_keyword
                - 항상 동일한 값을 포함되는 keyword filed인 경우
            - wildcard
                - 구조화되지 않은 기계 생성 콘텐츠의 경우. wildcard 유형은 값이 크거나 cardinality가 높은 필드에 최적화되어 있다.
        - keyword 필드는 sorting, aggregations, terms 와 같은 term-level queries에 자주 사용된다.
        - 전문 검색에는 keyword 필드가 아닌 text 필드 유형을 사용해야 한다.
            
            ### Keyword filed type
            
            - 다음은 기본 keyword 필드에 대한 mapping의 예다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "properties": {
                      "tags": {
                        "type":  "keyword"
                      }
                    }
                  }
                }
                ```
                
                ### Mapping numeric identifiers
                
                - 모든 숫자 데이터가 숫자 필드 데이터 유형으로 mapping되어야 하는 것은 아니다.
                - term, term-level 쿼리에는 키워드 필드가 더 적합하다.
                - keyword mapping은 다음과 같은 경우 숫자 식별자를 mapping하는 것을 고려할 수 있다.
                    - range 쿼리를 사용하여 식별자 데이터를 검색할 계획이 없는 경우
                    - 빠른 검색이 중요한 경우
                        - keyword 필드에 대한 term 검색은 종종 숫자 필드에 대한 term 검색보다 빠른 경우가 많다.
            
            ### Parameters for basic keyword fields
            
            - doc_values
                - 추후 sorting, aggregation, scripting에 사용될 수 있도록 field를 column-stride 방식으로 저장해야 하는 경우.
                - 기본 값 false
            - eager_global_ordinals
                - global ordinals를 새로 고칠 때 즉시 로드 여부.
                - 기본 값 false
                - terms aggregations에 자주 사용되는 필드에서는 활성화가 유리하다.
            - fields
                - 다양한 목적을 위해 동일한 문자열 값을 여러 방식으로 indexing가능 여부.
            - ignore_above
                - 설정된 값보다 긴 문자열 indexing을 막는다.
                - 기본 값은 214783647
                - 기본 동적 mapping 규칙은 ignore_above: 256을 설정하여 이 기본 값을 재정의 하는 하위 keyword 필드를 생성한다.
            - index
                - 기본 값 true.
                - doc_values만 활성화된 keyword 필드는 속도는 느리지만 쿼리할 수 있다.
            - index_options
                - 기본 값은 docs.
                - 점수를 계산할 때 용어 빈도를 고려하도록 freqs를 설정할 수도 있다.
            - meta
                - 필드에 대한 metadata
            - norms
                - 쿼리 counting을 매길 때 필드 길이를 고려해야 하는지 여부.
                - 기본 값은 false
            - null_value
                - 명시적인 null 값을 대체하는 문자열 값 허용.
                - 기본 값은 null
                - sciprt 값을 사용하는 경우에는 설정 불가능.
            - on_script_error
                - script 매개변수로 정의된 script가 indexing 시간에 오류를 발생 시키는 경우 수행할 작업 정의
                - 기본 값 fail을 수락할 경우 전체 문서를 거부하고, continue는 문서의 _ignored metadata 필드에 필드를 등록하고 indexing을 계속한다.
                - script가 설정된 경우에만 설정 가능
            - script
                - 필드는 소스에서 직접 값을 읽는 대신 이 스크립트에서 생성된 값을 indexing한다.
            - store
                - 필드 값을 _source 필드와 별도로 저장하고 검색할 수 있는지 여부
                - 기본 값은 false
            - similarity
                - 어떤 scoring 알고리즘 또는 similarity를 사용해야 하는지 결정한다.
                - 기본 값은 BM25
            - normalizer
                - indexing 전에 키워드를 사전 처리하는 방법.
                - 기본 값은 null로 키워드가 있는 그대로 유지된다.
            - split_queries_on_whitespace
                - 필드에 대한 쿼리를 작성할 때 전체 텍스트 쿼리가 공백에 대한 입력을 분할해야 하는지 여부.
                - 기본 값은 false
            - time_series_dimension
                - 선택 사항, boolean
                - 필드를 time series dimension으로 표시한다.
                - 기본 값은 false
                - 인덱스 설정 index.mapping.dimension_fields.limit은 인덱스의 차원 수를 제한한다.
                - doc_values 및 index의 mapping 매개변수는 true여야 한다.
                - 필드 값은 배열 또는 다중 값일 수 없다
                - 필드 값은 1024 바이트보다 클 수 없다
                - normalizer 필드는 사용할 수 없다.
            
            ### Synthetic _source
            
            <aside>
            💡 Synthetic _source는 일반적으로 TSDB 인덱스(index.mode가 time_series로 설정된 index)에 대해서만 사용 가능하다. preview 기술로 제거될 수 있다.
            
            </aside>
            
            - keyword 필드는 기본 구성에서 Synthetic_source를 지원한다.
            - Synthetic _source는 normalizer나 copy_to와 함께 사용할 수 없다.
            - Synthetic _source는 keyword 필드를 정렬하고 중복 항목을 제거한다.
            - 단, 필드가 keyword store:true로 설정되면 순서와 중복이 유지된다.
            - ignore_above보다 긴 값은 유지되지만 끝까지 정렬된다.
            
            ### Constant keyword field type
            
            - 상수 키워드는 인덱스의 모든 문서가 동일한 값을 갖는 경우에 대한 keyword 필드의 특수화이다.
                
                ```json
                PUT logs-debug
                {
                  "mappings": {
                    "properties": {
                      "@timestamp": {
                        "type": "date"
                      },
                      "message": {
                        "type": "text"
                      },
                      "level": {
                        "type": "constant_keyword",
                        "value": "debug"
                      }
                    }
                  }
                }
                ```
                
            - constant_keyword는 keyword 필드와 동일한 쿼리 및 집계를 지원하지만 모든 문서가 인덱스당 동일한 값을 갖는다는 사실을 활용하여 쿼리를 보다 효율적으로 실행한다.
            - 필드 값이 없거나 Mapping에 구성된 값과 동일한 값을 가진 문서를 제출하는 것이 모두 허용된다.
                
                ```json
                POST logs-debug/_doc
                {
                  "date": "2019-12-12",
                  "message": "Starting up Elasticsearch",
                  "level": "debug"
                }
                
                POST logs-debug/_doc
                {
                  "date": "2019-12-12",
                  "message": "Starting up Elasticsearch"
                }
                ```
                
            - 그러나 mapping에 구성된 값과 다른 값을 제공하는 것은 허용되지 않는다.
            - mapping에 값이 제공되지 않은 경우 필드는 첫 번째 indexing된 문서에 포함된 값을 기반으로 자동으로 구성된다.
            - 단, 이 동작은 하나의 유해한 문서로 인해 값이 잘못된 경우 다른 모든 문서가 거부될 수 있다.
            - mapping 또는 문서를 통해 값이 제공되기 전에는 필드에 대한 쿼리가 어떤 문서와도 일치하지 않는다. 여기에는 exists 쿼리가 포함된다.
            - 필드 값은 설정된 후에는 변경할 수 없다.
                
                ### Parameter for constant keyword fields
                
                - meta : 필드에 대한 metadata
                - value : 인덱스의 모든 문서와 연결할 값. 이 매개변수가 제공되지 않으면 index가 생성되는 첫 번째 문서를 기준으로 설정된다.
            
            ### Wildcard field tpye
            
            - wildcard 필드 유형은 grep과 유사한 wildcard 및 regexp 쿼리를 사용하여 검색하려는 구조화되지 않은 시스템 생성 콘텐츠에 대한 특수 키워드 필드다.
            - wildcard 유형은 값이 크거나 cardinality가 높은 필드에 최적화되어 있다.
                
                ### Mapping unstructured content
                
                - 구조화되지 않은 콘텐츠가 포함된 필드를 text 또는 keyword 계열 필드에 mapping할 수 있다.
                - text 필드 유형을 사용할 경우는 다음과 같다.
                    - 이메일 본문이나 제품 설명과 같은 콘텐츠.
                - keyword 필드 유형을 사용할 경우
                    - 로그 메시지나 HTTP 요청 정보와 같은 기계로 생성된 콘텐츠
                
                ### Choosing a keyword family field type
                
                - keyword 계열 필드 유형을 선택하는 경우 필드 값의 cardinality 및 크기에 따라 필드를 keyword 또는 필드로 mapping할 수 있다.
                - wildcard 또는 정규식 쿼리를 사용하여 필드를 정기적으로 검색하고 다음 기준 중 하나를 충족하는 경우 wildcard 유형을 고려할 수 있다.
                    - 필드에 고유 값이 많으며 *foo나 *baz와 같이 앞에 wildcard가 있는 패턴을 사용하여 필드를 정기적으로 검색할 예정인 경우
                    - 필드에 32KB보다 큰 값이 포함되어 있는 경우
                    - wildcard 패턴을 사용하여 필드를 정기적으로 검색할 계획인 경우
                - 그 외의 경우에는 더 빠른 검색, 더 빠른 indexing 및 더 낮은 저장 비용을 위해 keyword 필드 유형이 권장된다.
                
                ### Switching from a text field to a keyword field
                
                - 이전에 text 필드를 사용하여 구조화되지 않은 시스템 생성 콘텐츠를 indexing한 경우 다시 indexing하여 keyword 또는 wildcard 필드에 대한 mapping을 업데이트할 수 있다.
                - 또한 필드의 단어 기반 전체 텍스트 쿼리를 동등한 term 수준 쿼리로 바꾸도록 application이나 workflow를 update하는 것이 권장된다.
            - 내부적으로 wildcard 필드는 ngram을 사용하여 전체 필드 값을 indexing하고 전체 문자열을 저장한다.
            - 인덱스는 전체 값을 검색하고 확인하여 확인되는 값의 수를 줄이기 위한 대략적인 필터로 사용된다.
            - wildcard 필드는 로그 줄에서 grep과 유사한 쿼리를 실행하는 데 특히 적합하다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "properties": {
                      "my_wildcard": {
                        "type": "wildcard"
                      }
                    }
                  }
                }
                
                PUT my-index-000001/_doc/1
                {
                  "my_wildcard" : "This string can be quite lengthy"
                }
                
                GET my-index-000001/_search
                {
                  "query": {
                    "wildcard": {
                      "my_wildcard": {
                        "value": "*quite*lengthy"
                      }
                    }
                  }
                }
                ```
                
                ### Parameters for wildcard fields
                
                - null_value : 명시적인 null 값을 대체하는 문자열 값을 허용한다. 기본 값은 null
                - ignore_above : 기본 값은 2147483647, 설정된 값보다 긴 문자열은 indexing할 수 없다.
                
                ### Limitations
                
                - wildcard 필드는 keyword 필드처럼 토큰화되지 않으므로 구문 쿼리와 같이 단어 위치에 의존하는 쿼리를 지원하지 않는다
                - wildcard 쿼리를 실행할 때 rewrite 매개변수는 무시된다. score는 항상 일정하다.
            
            ### Synthetic _source
            
            - wildcard 필드는 copy_to를 선언하지 않는 한 Synthetic _source를 지원한다.
            - Synthetic _source는 항상 wildcard 필드를 정렬한다.
    - Nested
        
        ### Nested field type
        
        - nested 유형은 객체 배열을 서로 독립적으로 쿼리할 수 있는 방식으로 indexing할 수 있도록 하는 object 데이터 유형의 특수 버전이다.
            
            ### How arrays of objects are flattened
            
            - Elasticsearch에는 내부 개체에 대한 개념이 없다. 따라서 개체 계층을 필드 이름 및 값의 간단한 목록으로 flattened 한다.
                
                ```json
                PUT my-index-000001/_doc/1
                {
                  "group" : "fans",
                  "user" : [ 
                    {
                      "first" : "John",
                      "last" :  "Smith"
                    },
                    {
                      "first" : "Alice",
                      "last" :  "White"
                    }
                  ]
                }
                ```
                
                - 위 문서는 내부적으로 다음과 유사한 문서로 변환된다.
                    
                    ```json
                    {
                      "group" :        "fans",
                      "user.first" : [ "alice", "john" ],
                      "user.last" :  [ "smith", "white" ]
                    }
                    ```
                    
                - user.first와 user.last 필드는 다중 값 필드로 flattened 되며 alice 및 white 간의 연결이 손상된다.
            
            ### Using nested fields for arrays of objects
            
            - 객체의 배열을 indexing하고 배열에 있는 각 객체의 독립성을 유지해야 하는 경우 object 데이터 유형 대신 nested 데이터 유형이 권장된다.
            - 내부적으로 중첩된 객체는 배열의 각 객체를 별도의 숨겨진 문서로 indexing한다.
            - nested된 각 객체는 nested 쿼리를 사용하여 다른 객체와 독립적으로 쿼리할 수 있다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "properties": {
                      "user": {
                        "type": "nested" 
                      }
                    }
                  }
                }
                
                PUT my-index-000001/_doc/1
                {
                  "group" : "fans",
                  "user" : [
                    {
                      "first" : "John",
                      "last" :  "Smith"
                    },
                    {
                      "first" : "Alice",
                      "last" :  "White"
                    }
                  ]
                }
                
                GET my-index-000001/_search
                {
                  "query": {
                    "nested": {
                      "path": "user",
                      "query": {
                        "bool": {
                          "must": [
                            { "match": { "user.first": "Alice" }},
                            { "match": { "user.last":  "Smith" }} 
                          ]
                        }
                      }
                    }
                  }
                }
                
                GET my-index-000001/_search
                {
                  "query": {
                    "nested": {
                      "path": "user",
                      "query": {
                        "bool": {
                          "must": [
                            { "match": { "user.first": "Alice" }},
                            { "match": { "user.last":  "White" }} 
                          ]
                        }
                      },
                      "inner_hits": { 
                        "highlight": {
                          "fields": {
                            "user.first": {}
                          }
                        }
                      }
                    }
                  }
                }
                ```
                
            
            ### Interacting with nested documents
            
            - 다음과 같은 경우 nested documents라고 한다.
                - nested query를 사용하여 쿼리됨
                - nested 및 reverse_nested 집계로 분석됨
                - nested sorting로 정렬됨
                - nested inner hits로 검색되고 강조 표시됨.
                
                <aside>
                💡 nested 문서는 별도의 문서로 indexing 되므로 nested query, nested/reverse_nested 집계 또는 nested inner hits 내에서만 액세스할 수 있다.
                
                </aside>
                
            
            ### Parameters for nested fields
            
            - dynamic
                - 선택 사항, String
                - 기존 nested object에 새 속성을 동적으로 추가 해야 하는지 여부.
                - 기본 값 true
                - false 및 strict 허용
            - properteis
                - 선택 사항, object
                - nested를 포함하여 모든 데이터 유형이 될 수 있는 nested object 내의 필드.
                - 기존 중첩 개체에 새 속성을 추가할 수 있다.
            - include_in_parent
                - 선택 사항, boolean
                - true인 경우 nested object의 모든 필드가 상위 문서에도 표준(flat) 필드로 추가된다.
                - 기본 값은 false
            - include_in_root
                - 선택 사항, boolean
                - true인 경우 nested object의 모든 필드도 루트 문서에 표준(flat) 필드로 추가된다.
                - 기본 값은 false
            
            ### Limits on nested mappings and objects
            
            - 중첩된 각 개체는 별도의 Lucene 문서로 indexing된다.
                - index.mapping.nested_fields.limit
                    - nested 인덱스의 최대 개별 mapping 수.
                    - nested 유형은 객체 배열을 서로 독립적으로 쿼리해야 하는 특수한 경우에만 사용해야 한다.
                    - index당 고유한 nested 유형의 수를 제한한다.
                    - 기본 값은 50
                - index.mapping.nested_objects.limit
                    - 단일 문서가 모든 nested 유형에 포함될 수 있는 최대 중첩 JSON 객체 수.
                    - 기본 값은 1000
    - Numeric
        
        ### Numeric field types
        
        - long : 최솟값과 최댓값을 갖는 부호 있는 64비트 정수
        - integer : 최솟값과 최댓값을 갖는 부호 있는 32비트 정수
        - short : 최솟값 -32,768과 최댓값 32,767을 갖는 부호 있는 16비트 정수
        - byte : 최솟값 -128과 최댓값 127을 갖는 부호 있는 8비트 정수
        - double : 유한한 값으로 제한된 double-precision 64비트 IEEE 754 부동 소수점 숫자
        - float : 유한한 값으로 제한된 single-precision 32비트 IEEE 754 부동 소수점 숫자
        - half_float : 유한한 값으로 제한된 half-precision 16 비트 IEEE 754 부동 소수점 숫자
        - scaled_float : long 고정 배열 인수로 배율이 조정되고 double로 지원되는 부동 소수점 숫자
        - unsigned_long : 최솟값이 0이고 최댓값이 2^64 - 1인 부호 없는 64비트 정수
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "number_of_bytes": {
                    "type": "integer"
                  },
                  "time_in_seconds": {
                    "type": "float"
                  },
                  "price": {
                    "type": "scaled_float",
                    "scaling_factor": 100
                  }
                }
              }
            }
            ```
            
            ### Which type should I use?
            
            - 정수 유형(byte, short, integer, long)에 관한 한 사용 사례에 있어 가능한 가장 작은 유형을 선택해야 한다.
            - 스토리지는 저장된 실제 값을 기반으로 최적화되므로 다른 유형보다 한 유형을 선택하는 것이 스토리지 요구 사항에 영향을 미치지는 않는다.
            - 부동 소수점 유형의 경우 확장 계수(scaling factor)를 사용하여 부동 소수점 데이터를 정수로 저장하는 것이 더 효율적이다.
            - 정수는 부동 소수점보다 압축하기가 쉽기에 디스크 공간을 절약하는 데 대부분 도움이 된다.
            - scale_float이 적합하지 않으면 부동 소수점 유형 중에서 use-case에 적합한 가장 작은 유형(double, float, half_float)을 선택해야 한다.
                
                ![Untitled](Elastic_Guide(8.9)/Untitled.png)
                
            
            ### Parameters for numeric fields
            
            - coerce
                - 문자열을 숫자로 변환하고 정수의 경우 분수를 자른다.
                - 기본 값 true
                - unsigned_long에는 적용되지 않는다.
                - script 매개변수와 함께 사용할 수 없다.
            - doc_values
                - 추후 sorting, aggregation, scripting에 사용할 수 있도록 필드를 column-stride 방식으로 디스크에 저장할지 여부.
                - 기본 값 true
            - ignore_malformed
                - true인 경우 형식이 잘못된 숫자는 무시된다.
                - 기본 값 false인 경우 형식이 잘못된 숫자는 예외를 발생 시키고 전체 문서를 거부한다.
                - script 매개변수와 함께 사용할 수 없다.
            - index
                - 기본 값 true. doc_value만 활성화된 숫자 필드도 더 느리지만 쿼리할 수 있다.
            - meta
                - 필드에 대한 metadata
            - null_value
                - 명시적 null 값으로 대체되는 필드와 동일한 유형의 숫자 값을 사용한다.
                - 기본 값은 null
                - script 매개변수와 함께 사용할 수 없다.
            - on_script_error
                - 스크립트 매개 변수에 의해 정의된 스크립트가 indexing 시간에 오류를 발생 시킬 경우 수행할 작업을 정의한다.
                - 기본 값 fail을 설정할 경우 전체 문서를 거부하게 하며, continue는 문서의 _ignored metadata 필드에 필드를 등록하고 indexing을 계속한다.
                - script 필드가 설정된 경우에만 설정 가능
            - script
                - 필드는 소스에서 값을 직접 읽지 않고 스크립트에서 생성된 값을 indexing한다.
                - script는 long, double 필드 유형에서만 구성 가능하다.
            - store
                - 필드 값을 _source 필드와 별도로 저장하고 검색할 수 있는지 여부
                - 기본 값은 true
            - time_series_dimension
                - 선택 사항, boolean
                - 필드를 time_series_dimension으로 표시한다.
                - 기본 값은 false
                - index.mapping.dimension_fields.limit 인덱스 설정은 인덱스의 차원 수를 제한한다.
                - doc_values 및 index mapping 매개변수는 true 여야 한다.
                - 필드 값은 배열 또는 다중 값일 수 없다.
                - 숫자 필드 유형 중 byte, short, integer, long, unsigned_long 필드만 이 매개변수를 지원한다.
                - 숫자 필드는 time series dimension이면서 time series metric일 수 없다.
            - time_series_metric
                - 선택 사항, String
                - 필드를 time series metric으로 표시한다.
                - 값은 측정 항목 유형이며 기존 필드에 대해서는 이 매개변수를 업데이트할 수 없다.
                - time_series_metric 숫자 필드에 유효한 값
                    - counter : 단조롭게 증가하거나 0으로 재 설정되는 누적 측정 항목
                    - gauge : 임의로 증가하거나 감소할 수 있는 단일 숫자를 나타내는 측정항목
                    - null (default) : time_series_metric이 아니다.
                - time series metric의 경우 doc_value 매개변수는 true여야 한다. 숫자 필드는 time series dimension이면서 time series metric일 수 없다.
            
            ### Parameters for scaled_float
            
            - scaled_float 추가 매개변수를 허용한다.
            - scaling_factor
                - 값을 사용할 때 사용할 배율 인수. 인덱스 시간에 값에 이 요소를 곱하고 가장 가까운 긴 값으로 반올림 된다.
                - scaling_factor의 높은 값은 정확도를 향상 시키지만 또한 공간 요구 사항을 증가 시킨다.
                - scaled_float를 위해선 scaling_factor 매개변수가 필요하다.
            
            ### Synthetic _source
            
            <aside>
            💡 Synthetic _source는 일반적으로 TSDB 인덱스(index.mode가 time_series로 설정된 index)에 대해서만 사용 가능하다. preview 기술로 제거될 수 있다.
            
            </aside>
            
            - unsigned_long을 제외한 모든 숫자 필드는 기본 구성에서 synthetic _source를 지원한다. synthetic _source는 ignore_malformed, copy_to, doc_value가 비활성 된 상태에서 사용할 수 없다.
            - synthetic _source는 숫자 필드를 항상 정렬한다.
            - scaled floats는 항상 scaling factor를 적용한다.
    - Object
        
        ### Object field type
        
        - JSON 문서는 기본적으로 계층구조로 되어 있다. 문서는 내부 객체를 포함할 수 있으며 내부 객체 자체를 포함할 수 있다.
            
            ```json
            PUT my-index-000001/_doc/1
            { 
              "region": "US",
              "manager": { 
                "age":     30,
                "name": { 
                  "first": "John",
                  "last":  "Smith"
                }
              }
            }
            ```
            
        - 내부적으로 위 요청은 다음의 키-값 쌍의 단순한 목록으로 indexing된다.
            
            ```json
            {
              "region":             "US",
              "manager.age":        30,
              "manager.name.first": "John",
              "manager.name.last":  "Smith"
            }
            ```
            
        - 필드 유형을 명시적으로 object로 설정할 필요는 없다. object는 기본 값으로 되어있기 때문.
            
            ### Parameters for object fields
            
            - dynamic
                - 기존 객체에 새 속성을 동적으로 추가해야 하는지 여부
                - 기본 값은 true.
                - runtime, false, strict를 허용
            - enabled
                - 객체 필드에 제공된 JSON 값을 구문 분석하고 indexing해야 하는지(true, 기본 값) 아니면 완전히 무시해야 하는지(false) 여부
            - subobjects
                - 객체가 하위 객체를 보유할 수 있는지(true, 기본 값) 혹은 아닌지(false) 여부.
                - false인 경우 이름에 점이 있는 하위 필드는 leaves로 표현된다.
                - 그렇지 않으면, 해당 필드 이름이 해당 객체 구조로 확장된다.
            - properties
                - 객체를 포함하여 모든 데이터 유형이 될 수 있는 객체 내의 필드
                - 기존 객체에 새로운 속성을 추가할 수 있다.
    - Percolator
        
        ### Percolator field type
        
        - percolator 유형은 json 구조를 기본 쿼리로 구문 분석하고 해당 쿼리를 저장하므로 percolate query가 이를 사용하여 제공된 문서와 일치 시킬 수 있다.
        - json 객체가 포함된 모든 필드는 percolator field로 구성될 수 있다.
        - percolator field 유형에는 설정이 없으며, 필드 유형을 구성하는 것 만으로도 Elasticsearch가 percolator 필드를 쿼리로 처리하도록 지시하기에 충분하다.
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "query": {
                    "type": "percolator"
                  },
                  "field": {
                    "type": "text"
                  }
                }
              }
            }
            ```
            
            <aside>
            💡 percolator 쿼리에서 참조되는 필드는 percolation에 사용되는 인덱스와 연결된 mapping에 이미 존재해야 한다.
            
            </aside>
            
            ### Reindexing your percolator queries
            
            - reindexing percolator 쿼리는 reindex api를 사용하여 다시 색인화할 수 있다.
            - reindex는 다음과 같은 방식으로 이루어진다.
                
                ```json
                PUT new_index
                {
                  "mappings": {
                    "properties": {
                      "query" : {
                        "type" : "percolator"
                      },
                      "body" : {
                        "type": "text"
                      }
                    }
                  }
                }
                
                POST /_reindex?refresh
                {
                  "source": {
                    "index": "index"
                  },
                  "dest": {
                    "index": "new_index"
                  }
                }
                
                POST _aliases
                {
                  "actions": [ 
                    {
                      "remove": {
                        "index" : "index",
                        "alias": "queries"
                      }
                    },
                    {
                      "add": {
                        "index": "new_index",
                        "alias": "queries"
                      }
                    }
                  ]
                }
                ```
                
            - queries 별칭을 통해 percolate 쿼리 실행
                
                ```json
                GET /queries/_search
                {
                  "query": {
                    "percolate" : {
                      "field" : "query",
                      "document" : {
                        "body" : "fox jumps over the lazy dog"
                      }
                    }
                  }
                }
                ```
                
            
            ### Optimizing query time text analysis
            
            - percolator가 분석할 percolator candidate match를 확인할 때 쿼리 시간 텍스트 분석을 수행하고 percolated 문서에 대해 percolator 쿼리를 실제로 실행한다.
            - 이는 각 후보 일치에 대해, 그리고 percolate 쿼리가 실행될 때마다 수행된다.
            - percolator 흐름을 indexing하기 전에 analyze API는 각 percolator 쿼리에 대해 수행되어야 한다.
            - percolator 시에는 아무것도 변경되지 않으며 percolate 쿼리는 정상적으로 정의될 수 있다.
                
                ```json
                GET /test_index/_search
                {
                  "query": {
                    "percolate" : {
                      "field" : "query",
                      "document" : {
                        "body" : "Bycicles are missing"
                      }
                    }
                  }
                }
                ```
                
            
            ### Optimizing wildcard queries
            
            - wildcard 쿼리는 percolate에 대한 다른 쿼리보다 비용이 더 많이 든다. 특히 wildcard 표현식이 큰 경우 더욱 그렇다.
            - 접두사 wildcard 표현식이 포함된 wildcard 쿼리 또는 접두사 쿼리만 있는 경우 edge_ngram 토큰 필터를 사용하여 edge_ngram 토큰 필터가 구성된 필드에서 이러한 쿼리를 일반 용어 쿼리로 바꿀 수 있다.
            
            ### Dedicated Percolator Index
            
            - Percolate 쿼리는 모든 인덱스에 추가될 수 있다.
            - 데이터가 있는 인덱스에 percolate 쿼리를 추가하는 대신 이러한 쿼리를 전용 인덱스에 추가할 수도 있다.
            - 이러한 방식의 장점은 이 전용 percolate 인덱스가 자체 인덱스 설정을 가질 수 있다는 것이다.
            
            ### **Forcing Unmapped Fields to be Handled as Strings**
            
            - Percolate 쿼리에서 참조하는 필드에 대한 필드 mapping이 없으면 percolate 쿼리 추가가 실패한다. 이는 해당 필드가 적절한 설정으로 포함되도록 mapping을 업데이트해야 하며 그런 다음 percolate 쿼리를 추가할 수 있음을 의미한다.
            - 이와 같은 경우 index.percolate.map_unmapped_fields_as_text 설정을 true(기본 값은 false)로 구성함으로서 percolate 쿼리에서 참조된 필드가 존재하지 않으면 percolate 쿼리를 추가해도 해당 필드가 기본 텍스트 필드로 처리되게 할 수 있다. 즉, 실패를 방지할 수 있다.
            
            ### Limitations
            
            ### Parent/child
            
            - Percolate 쿼리는 한 번에 하나의 문서를 처리하기에 has_child 및 has_parent와 같은 하위 문서에 대해 실행되는 쿼리 및 필터를 지원하지 않는다.
            
            ### Fetching queires
            
            - 쿼리 구문 분석 중 get 호출을 통해 데이터를 가져오는 쿼리가 percolate 필드 유형으로 indexing되면 get 호출이 한 번 실행된다. 따라서 percolate 쿼리가 이러한 query를 평가할 때마다 인덱스 시간에 있었던 fetches terms, shapes 등이 사용된다.
            - 중요한 점은 이러한 쿼리가 primary shard와 replica shard 모두에서 indexing될 때마다 발생하므로 indexing 중에 소스 인덱스가 변경된 경우 실제로 indexing되는 용어가 shard replica 간에 다를 수 있다는 점이다.
            
            ### Script query
            
            - 스크립트 쿼리 내의 스크립트는 문서 값 필드에만 액세스할 수 있다. percolate 쿼리는 제공된 문서를 메모리 내 인덱스로 indexing한다.
            - 이 in-memory 인덱스는 저장 필드를 지원하지 않으며 이로 인해 _source 필드 및 기타 저장 필드가 저장되지 않는다.
            - 이것이 스크립트 쿼리에서 _source 및 기타 stored 필드를 사용할 수 없는 이유다.
            
            ### Field aliases
            
            - field aliases이 포함된 percolator 쿼리는 예상대로 작동하지 않을 수 있다. 특히, 필드 별칭을 포함하는 percolator 쿼리가 등록된 다음 해당 별칭이 mapping에서 업데이트 되어 다른 필드를 참조하는 경우 저장된 쿼리는 계속 원래 대상 필드를 참조한다.
            - 필드 별칭에 대한 변경 사항을 선택하려면 percolate 쿼리를 명시적으로 다시 indexing해야 한다.
    - Point
        
        ### Point field type
        
        - point 데이터 유형은 2차원 평면 좌표계에 속하는 임의 x, y 쌍의 indexing 및 검색을 용이하게 한다.
        - shape 쿼리를 사용하여 이 유형을 사용하는 문서를 쿼리할 수 있다.
        - geo_shape 및 geo_point와 마찬가지로 point 유형은 GeoJSON 및 Well-Knwo Text 형식으로 지정할 수 있다.
        - cartesian point를 지정할 수 있는 방법은 아래의 총 5가지가 있다.
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "location": {
                    "type": "point"
                  }
                }
              }
            }
            
            PUT my-index-000001/_doc/1
            {
              "text": "Point as an object using GeoJSON format",
              "location": { 
                "type": "Point",
                "coordinates": [-71.34, 41.12]
              }
            }
            
            PUT my-index-000001/_doc/2
            {
              "text": "Point as a WKT POINT primitive",
              "location" : "POINT (-71.34 41.12)" 
            }
            
            PUT my-index-000001/_doc/3
            {
              "text": "Point as an object with 'x' and 'y' keys",
              "location": { 
                "x": -71.34,
                "y": 41.12
              }
            }
            
            PUT my-index-000001/_doc/4
            {
              "text": "Point as an array",
              "location": [ -71.34, 41.12 ] 
            }
            
            PUT my-index-000001/_doc/5
            {
              "text": "Point as a string",
              "location": "-71.34,41.12" 
            }
            ```
            
            - geo-point 필드 유형의 경우와 달리 좌표의 순서 x는 y 위의 모든 형식에서 동일하다.
            
            ### Parameters for point fields
            
            - ignore_malformed
                - true이면 잘못된 점이 무시된다.
                - 기본 값 false인 경우 잘못된 점이 예외를 발생 시키고 전체 문서를 거부한다.
            - ignore_z_value
                - true(기본 값)인 경우 3개의 차원 점이 허용되지만 x및 y값만 indexing된다.
                - 세 번째 차원은 무시된다.
                - false이면 x 및 y 값보다 큰 값을 포함하는 포인트는 예외를 발생 시키고 전체 문서를 거부한다.
            - null_value
                - 명시적인 null 값을 대체하는 포인트 값을 허용한다.
            
            ### Sorting and retrieving points
            
            - 8.9버전 기준 포인트를 정렬하거나 해당 필드를 직접 검색하는 것은 불가능하다. point 값은 _source 필드를 통해서만 검색할 수 있다.
    - Range
        
        ### Range field types
        
        - 범위 필드 유형은 상한과 하한 사이의 연속적인 값 범위를 나타낸다.
        - 하한에 대해 연산자 gt 또는 gte를, 상한에 대해 lt 또는 lte 연산자를 사용하여 정의된다.
        - 쿼리에 사용할 수 있으며 aggregation에 대한 지원은 제한된다.
        - 유일하게 지원되는 aggregation은 histogram, cardinality다.
        - 지원되는 range type은 다음과 같다.
            - integer_range
                - 최솟값과 최댓값을 갖는 부호 있는 32비트 정수 범위
            - float_range
                - single-precision 32비트 IEEE 754 부동 소수점 값의 범위
            - long_range
                - 최솟값과 최댓값을 갖는 부호 있는 64비트 정수 범위
            - double_range
                - double-precision 64비트 IEEE754 부동 소수점 값의 범위
            - date_range
                - date 값의 범위.
                - 날짜 범위는 format mapping 매개변수를 통해 다양한 날짜 형식을 지원한다.
                - 사용된 형식에 관계없이 날짜 값은 UTC 기준 Unix 시대 이후의 밀리초를 나타내는 부호 없는 64비트 정수로 구문 분석된다.
                - now 날짜 수학 표현식이 포함된 값은 지원되지 않는다.
            - ip_rangec
                - IPv4 또는 IPv6 주소를 지원하는 IP 값 범위
            
            ```json
            PUT range_index
            {
              "settings": {
                "number_of_shards": 2
              },
              "mappings": {
                "properties": {
                  "expected_attendees": {
                    "type": "integer_range"
                  },
                  "time_frame": {
                    "type": "date_range", 
                    "format": "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd||epoch_millis"
                  }
                }
              }
            }
            
            PUT range_index/_doc/1?refresh
            {
              "expected_attendees" : { 
                "gte" : 10,
                "lt" : 20
              },
              "time_frame" : {
                "gte" : "2015-10-31 12:00:00", 
                "lte" : "2015-11-01"
              }
            }
            ```
            
            ### IP Range
            
            - CIDR 표기법으로 IP 범위를 제공할 수도 있다.
                
                ```json
                PUT range_index/_mapping
                {
                  "properties": {
                    "ip_allowlist": {
                      "type": "ip_range"
                    }
                  }
                }
                
                PUT range_index/_doc/2
                {
                  "ip_allowlist" : "192.168.0.0/16"
                }
                ```
                
            
            ### Parameters for range fields
            
            - coerce
                - 문자열을 숫자로 변환하고 정수의 경우 분수를 자른다.
                - 기본 값  true
            - index
                - 해당 필드 검색 가능 여부
                - 기본 값 true
            - store
                - 필드 값을 _source 필드와 별도로 저장하고 검색할 수 있는지 여부.
                - 기본 값 false
    - Rank feature
        
        ### Rank feature field type
        
        - rank_feature 필드는 rank_feature 쿼리를 사용하여 쿼리에서 문서를  강화하는 데 사용할 수 있도록 숫자를 indexing할 수 있다.
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "pagerank": {
                    "type": "rank_feature" 
                  },
                  "url_length": {
                    "type": "rank_feature",
                    "positive_score_impact": false 
                  }
                }
              }
            }
            
            PUT my-index-000001/_doc/1
            {
              "pagerank": 8,
              "url_length": 22
            }
            
            GET my-index-000001/_search
            {
              "query": {
                "rank_feature": {
                  "field": "pagerank"
                }
              }
            }
            ```
            
        - rank_feature 필드는 단일 값 필드와 순양수(strictly positive values) 값만 지원한다. 다중 값 필드와 음수 값은 거부된다.
        - rank_feature 필드는 쿼리, 정렬, 집계를 지원하지 않는다. rank_feature 쿼리 내에서만 사용할 수 있다.
        - rank_feature 필드는 정밀도를 위해 9개의 유효 비트만 보존하며 이는 약 0.4%의 상대 오류로 해석된다.
        - 정수와 음의 상관 관계가 있는 순위 기능은 positive_socre_impact를 false로 설정해야 한다.  (기본 값은 false)
        - 이는 rank_feature 쿼리에서 점수가 증가하는 대신 기능 값에 따라 감소하는 방식으로 점수 공식을 수정하는 데 사용된다.
    - Rank features
        
        ### Rank features field type
        
        - rank_features 필드는 숫자 특정 벡터를 indexing할 수 있으므로 추후 rank_feature 쿼리를 사용하여 쿼리에서 문서를 강화(boost)하는 데 사용할 수 있다.
        - 이는 rank_feature 데이터 유형과 유사하지만 기능 목록이 희박하여 각 기능에 대한 mapping에 하나의 필드를 추가하는 것이 합리적이지 않을 때 더 작합하다.
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "topics": {
                    "type": "rank_features" 
                  },
                  "negative_reviews" : {
                    "type": "rank_features",
                    "positive_score_impact": false 
                  }
                }
              }
            }
            
            PUT my-index-000001/_doc/1
            {
              "topics": { 
                "politics": 20,
                "economics": 50.8
              },
              "negative_reviews": {
                "1star": 10,
                "2star": 100
              }
            }
            
            PUT my-index-000001/_doc/2
            {
              "topics": {
                "politics": 5.2,
                "sports": 80.1
              },
              "negative_reviews": {
                "1star": 1,
                "2star": 10
              }
            }
            
            GET my-index-000001/_search
            {
              "query": { 
                "rank_feature": {
                  "field": "topics.politics"
                }
              }
            }
            
            GET my-index-000001/_search
            {
              "query": { 
                "rank_feature": {
                  "field": "negative_reviews.1star"
                }
              }
            }
            ```
            
        - rank_features 필드는 단일 값 기능과 엄격한 양수 값만 지원된다. 다중 값 필드와 0 또는 음수 값은 거부된다.
        - rank_features 필드는 정렬이나 집계를 지원하지 않으며 rank_feature 또는 term 쿼리를 사용해서만 쿼리할 수 있다.
        - rank_features 필드에 대한 term 쿼리는 일치하는 저장된 특성 값에 제공된 boost를 곱하여 점수를 매긴다.
        - rank_features 필드는 정밀도를 위해 9개의 유효 비트만 보존하며 이는 약 0.4%의 상대 오류로 해석된다.
        - 점수와 음의 상관관계가 있는 순위 기능은 positive_score_impact를 true로 설정되어야 한다. (기본 값은 true)
        - 이는 점수가 증가하는 대신 기능 값에 따라 감소하는 방식으로 점수 공식을 수정하기 위해 rank_feature 쿼리에서 사용된다.
    - Search-as-you-type
        
        ### Search-as-you-type field type
        
        - search_as_you_type필드 유형은 입력 시 완성 사용 사례를 제공하는 쿼리에 대한 기본 지원을 제공하도록 최적화된 텍스트와 같은 필드다.
        - 전체 inexing된 텍스트 값과 부분적으로 일치하는 쿼리로 효율적으로 일치할 수 있는 index 용어로 분석되는 일련의 하위 필드를 생성한다.
        - 접두어 완성과 중위 완성이 모두 지원된다.
        - search_as_you_type 필드를 mapping에 추가하는 경우
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "my_field": {
                    "type": "search_as_you_type"
                  }
                }
              }
            }
            ```
            
        - 아래의 필드가 생성된다.
            - my_field
                - mapping에 구성된 대로 분석된다.
                - 분석기가 구성되지 않은 경우 index의 기본 분석기가 사용된다.
            - my_field._2gram
                - shingle 크기 2의 shingle 토큰 필터로 my_field 분석기를 wrapping한다.
            - my_field._3gram
                - shingle 크기 3의 shingle 토큰 필터로 my_field 분석기를 wrapping한다.
            - my_field._index_prefix
                - Edge ngram 토큰 필터로 my_field._3gram 분석기를 wrapping한다.
        - 하위 필드의 shingle 크기는 max_shingle_size mapping 매개변수를 사용하여 구성할 수 있다.
        - 기본 값은 3이고 매개변수의 유효한 값은 2 - 4를 포함한 정수 값이다.
        - Shingle 하위 필드는 max_shingle_size를 포함하여 2부터 각 shingle 크기에 대해 생성된다.
        - my_field._index_prefix 하위 필드는 자체 분석기를 구성할 때 항상 shingle 하위 필드의 분석기를 사용한다.
        - max_shingle_size를 늘리면 인덱스 크기가 커지는 것이 아닌 더 많은 consecutive terms가 포함된 쿼리에 대한 일치가 향상된다. 일반적으로 기본 값이면 충분하다.
        - indexing된 문서에 루트 필드 my_field에 대한 값이 있으면 동일한 입력 텍스트가 서로 다른 분석 체인을 사용하여 이러한 각 필드에 자동으로 indexing된다.
        - 입력에 따른 검색 사용 사례를 제공하기 위한 가장 효율적인 쿼리 방법은 일반적으로 루트 search_as_you_type 필드와 해당 shingle 하위 필드를 대상으로 하는 bool_prefix 유형의 multi_match 쿼리다.
        - 쿼리 용어와 순서대로 일치하는 문서를 검색하거나 phrase 쿼리의 다른 속성을 사용하여 검색하려면 루트 필드에 match_phrase_prefix 쿼리를 사용한다.
        - 마지막 용어가 접두사가 아닌 정확하게 일치해야 하는 경우에는 match_phrase 쿼리를 사용할 수도 있다.
        - phrase 쿼리를 사용하는 것은 match_bool_prefix 쿼리를 사용하는 것보다 효율성이 떨어질 수도 있다.
            
            ### Parameters specific to the search_as_you_type field
            
            - max_shingle_size
                - 선택 사항, Integer
                - 필드 mapping에 허용되며 search_as_you_type 필드 유형에만 적용된다.
                - 생성할 최대 shingle 크기.
                - 유효한 값은 2 부터 4까지이며, 기본 값은 3이다.
                - 2부터 max_shingle_size 값 사이의 각 정수에 대해 하위 필드가 생성된다. 값 3은 my_field._2gram, my_field._3gram의 두 개가 생성된다.
                - 하위 필드가 많을수록 더 구체적인 쿼리가 가능하지만 인덱스 크기가 늘어난다.
            
            ### Parameters of the field type as a text field
            
            - analyzer
                - 인덱스 시간과 검색 시간 모두에서 text 필드에 사용해야 하는 분석기. (search_analyzer로 재정의되지 않는 한)
                - 기본 값은 기본 인덱스 분석기 또는 standard analyzer
            - index
                - 해당 필드 검색 가능 여부.
                - 기본 값 true
            - index_options
                - 검색 및 강조 표시를 위해 색인에 어떤 정보를 저장해야 할지 확인.
                - 기본 값은 positions
            - norms
                - 쿼리 점수를 매길 때 필드 길이를 고려해야 하는지 여부. true 또는 false 옵션은 루트 필드와 shingle 하위 필드를 구성하며 기본 값은 true이다.
                - false일 경우 접두사 하위 필드는 구성하지 않는다.
            - store
                - 필드 값을 _source 필드와 별도로 저장하고 검색할 수 있는지 여부
                - 기본 값은 false
                - 이 옵션은 루트 필드만 구성하고 하위 필드는 구성하지 않는다.
            - search_analyzer
                - text 필드 검색 시 사용해야 하는 analyzer.
                - 기본 값은 search_analyzer
            - search_quote_analyzer
                - phrase이 발견될 때 검색 시 사용해야 하는 analyzer.
                - 기본 값은 search_analyzer
            - similarity
                - 어떤 score 알고리즘이나 similarity를 사용해야 할지 결정.
                - 기본 값은 BM25
            - term_vector
                - 필드에 대해 term vector를 저장해야 하는지 여부
                - 기본 값은 no
                - 루트 필드와 shingle 하위 필드를 구성하지만 접두사 하위 필드는 구성하지 않는다.
            
            ### Optimization of prefix queries
            
            - 루트 필드 또는 해당 하위 필드에 대한 접두사 쿼리를 만들면 쿼리는 ._index_prefix 하위 필드의 term 쿼리로 다시 작성된다.
            - 각 shingle의 특정 길이까지의 접두어는 ._index_prefix 하위 필드의 term로 직접 indexing되므로 이는 텍스트 필드의 일반적인 접두어 쿼리보다 더 효율적으로 일치한다.
            - ._index_prefix 하위 필드의 분석기는 일반적으로 shingle로 생성되지 않는 필드 값 끝에 있는 terms의 접두어를 indexing하기 위해 shingle building behavior를 약간 수정한다.
    - Shape
        
        ### Shape field type
        
        - Shape 데이터 유형은 직사각형 및 다각형과 같은 임의의 cartesian 모양에 대한 inexing 및 검색을 용이하게 한다. 좌표가 2차원 평면 좌표계에 속하는 형상을 indexing하고 쿼리하는 데 사용할 수 있다.
        - shape 쿼리를 사용하여 shape 유형 문서를 쿼리할 수 있다.
            
            ### Mapping Options
            
            - geo_shape 필드 유형과 마찬가지로 shape 필드 유형은 GeoJSON 또는 WKT(Well-Know-Text) 기하학 객체를 shape 유형에 mapping한다.
            - 이를 활성화하려면 사용자가 필드를 shape 유형에 명시적으로 mapping해야 한다.
                
                
                | 옵션 | 설명 | 기본 값 |
                | --- | --- | --- |
                | orientation | - 선택적으로 다각형.다중 다각형의 정점 순서를 해석하는 방법을 정의한다. 
                
                - 매개변수는 두 가지 좌표계 규칙(오른쪽 또는 왼쪽) 중 하나를 정의하며 각 규칙은 세 가지 다른 방법으로 지정할 수 있다. 
                
                - Right : right, ccw, counterclockwise
                
                - Left : left, cw, clockwise
                
                - 기본 방향(counterclockwise)은 외부 링 정점을 시계 반대 방향으로 정의하고 내부 링 정점(구멍)을 시계 방향으로 정의하는 OGC 표준을 따른다. 
                
                - mapping에서 orientation 매개변수 설정 시 geo_shape 필드의 좌표 목록에 대한 정점 순서가 명시적으로 설정되지만, geo_shpae 각 개별 GeoJSON 또는 WKT 문서에서 재정의 될 수 있다. | ccw |
                | ignore_malformed | - true인 경우 잘못된 GeoJSON 또는 WKT 모양이 무시된다. 
                
                - false인 경우 잘못된 GeoJSON 및 WKT 모양은 예외를 발생 시키고 전체 문서를 거부한다. | false |
                | ignore_z_value | - true인 경우 3개의 차원 점이 허용 되지만 위도 및 경도 값만 indxing된다. 세 번째 차원은 무시된다. 
                
                - false의 경우 위도 및 경도 이상의 값을 포함하는 geo-point는 예외를 발생 시키고 전체 문서를 거부한다. | true |
                | coerce | true인 경우 다각형의 닫히지 않은 선형 링이 자동으로 닫힌다. | false |
            
            ### Indexing approach
            
            - geo_shape 필드와 마찬가지로 shape 유형은 형상을 삼각형 mesh로 분해하고 각 삼각형을 BKD 트리의 7차원 점으로 indexing하여 indexing된다.
            - indexer에 제공되는 좌표는 single precision 부동 소수점 값이므로 필드는 JVM에서 제공하는 것과 일반적으로 동일한 정확도를 보장한다.
            - 다각형/다중 다각형의 경우 tessellator의 성능은 주로 형상을 정의하는 정점 수에 따라 달라진다.
                
                <aside>
                💡 CONTAINS 관계 쿼리 - 포함으로 정의된 관계가 있는 shape 쿼리는 Elasticsearch 7.5.0 이상으로 생성된 인덱스에 대해 지원된다.
                
                </aside>
                
                ```json
                PUT /example
                {
                  "mappings": {
                    "properties": {
                      "geometry": {
                        "type": "shape"
                      }
                    }
                  }
                }
                ```
                
            
            ### Input Structure
            
            - 도형은 GeoJSON 또는 WKT 형식을 사용하여 표현할 수 있다.
            
            | GeoJSON 유형 | WKT 유형 | Elasticsearch 유형 | 설명 |
            | --- | --- | --- | --- |
            | Point | POINT | point | 단일 지리적 좌표 |
            | LineString | LINESTRING | linestring | 두 개 이상의 점이 주어진 임의의 선 |
            | Polyjon | POLYGON | polygon | 첫 번째 점과 마지막 점이 일치해야 하는 닫힌 가각형 |
            | MultiPoint | MULTIPOINT | multipoint | 연결되지는 않았지만 관련 가능성이 높은 지점의 배열 |
            | MultiLineString | MULTILINESTING | multilinestring | 별도의 linestring 배열 |
            | MultiPolygon | MULTIPOLYGON | multipolygon | 별도의 다각형 배열 |
            | GeometryCollection | GEOMETRYCOLLECTION | geometrycollection | 여러 유형이 공존할 수 있는, 도형과 유사한 GeoJSON 모형 |
            | N/A | BBOX | envelope | 왼쪽 상단과 오른쪽 하단 점만 지정하여 지정되는 경계 사각형 또는 봉투 |
            - 모든 유형에는 내부 필드 type과 coordinates 필드가 모두 필요하다.
            - GeoJSON과 WKT, Elasticsearch에서 올바른 좌표 순서는 좌표 배열 내에서 (X, Y)이다.
            - 이는 일반적으로 구어체 위도, 경도 순서를 사용하는 많은 지리 공간과 다르다.
            - 필드 목록은 geo_shape 항목 참조
            
            ### Sorting and Retrieving Index Shapes
            
            - 복잡한 입력 구조와 도형의 인덱스 표현으로 인해 현재는 도형을 정렬하거나 해당 필드를 직접 검색하는 것이 불가능하다. shape 값은 _source를 통해서만 검색할 수 있다.
    - Text
        
        ### Text type family
        
        - text 계열에는 다음 필드 유형이 포함된다.
            - text는 이메일 본문이나 제품 설명과 같은 전체 텍스트 콘텐츠에 대한 전통적인 필드 유형이다
            - match_only_text는 scoring을 비활성화하고 위치가 필요한 쿼리에서 더 느리게 수행되는 공간 최적화된 텍스트 변형이다. 로그 메시지를 색인화하는 데 가장 적합하다.
            
            ### Text field type
            
            - 전체 텍스트 값을 indexing하는 필드
            - index되기 전에 문자열을 개별 용어 목록으로 변환하기 위해 분석기를 통과한다.
            - text field는 정렬에 사용되지 않으며 집계에 거의 사용되지 않는다.
            - 텍스트 필드는 구조화되지 않았지만 사람이 읽을 수 있는 콘텐츠에 가장 적합하다.
            - 구조화된 콘텐츠를 indexing해야 할 경우에는 keyword 필드를 사용해야 할 가능성이 높다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "properties": {
                      "full_name": {
                        "type":  "text"
                      }
                    }
                  }
                }
                ```
                
            
            ### Use a field as both text and keyword
            
            - 때로는 동일한 필드의 전체 text 및 keyword 버전을 모두 갖는 것이 유용할 수 있다. 하나는 전체 텍스트 검색 용, 다른 하나는 집계 및 정렬용이다.
            - 이는 multi-field를 사용하여 달성할 수 있다.
            
            ### Parameters for text fields
            
            - analyzer
                - 인덱스 시간과 검색 시간 모두 필드에서 사용해야 하는 분석기. search_analyzer로 재 정의되지 않는 한
                - 기본 값은 기본 인덱스 분석기 또는 analyzer이다.
            - eager_global_ordinals
                - 새로 고침 시 global ordinals가 즉시 불러와야 하는지 여부.
                - 기본 값은 false.
            - fielddata
                - 필드에서 정렬, 집계, scripting을 위해 메모리 내 필드 데이터 사용 여부.
                - 기본 값은 false
            - fielddata_frequency_filter
                - fielddata가 활성화되면 메모리에 불러올 값을 결정할 수 있는 전문가 설정
            - fields
                - 다중 필드를 사용하면 검색을 위한 하나의 필드와 정렬 및 집계를 위한 다중 필드, 또는 서로 다른 분석기로 동일한 문자열 값을 분석하는 등 다양한 목적을 위해 여러 방법으로 동일한 문자열 값을 indexing할 수 있다.
            - index
                - 해당 필드 검색 가능 여부.
                - 기본 값은 true
            - index_options
                - 검색 및 강조 표시를 위해 index에 저장 할 정보.
                - 기본 값은 positions
            - index_prefixes
                - 활성화된 경우 2~5자 사이의 용어 접두어 가 별도의 필드에 indexing된다.
                - 이를 통해 더 큰 인덱스를 희생하더라도 접두사 검색을 보다 효율적으로 실행할 수 있다.
            - index_phrases
                - 활성화된 경우 두 단어로 구성된 단어 조합(shingles)이 별도의 필드에 indexing된다.
                - 이를 통해 더 큰 인덱스를 희생하면서 정확한 phrase queries(no slop)을 보다 효율적으로 실행할 수 있다.
                - 불용어가 포함된 문구는 보조 필드(subsidiary field)를 사용하지 않고 표준 문구 쿼리(standard phrase query)로 돌아가기에 이는 불용어가 제거되지 않을 때 가장 잘 작동한다.
                - 기본 값은 false
            - norms
                - 쿼리 scoring 시 필드 길이 고려 여부.
                - 기본 값은 true
            - position_increment_gap
                - 문자열 배열의 각 요소 사이에 삽입되어야 하는 가짜 용어 위치의 수.
                - 기본적으로 분석기에 구성된 position_increment_gap의 기본 값은 100
            - store
                - 필드 값을 _source 필드와 별도로 저장하고 검색할 수 있는지 여부.
                - 기본 값은 false
            - search_analyzer
                - text 필드에서 검색 시 사용해야 하는 분석기 종류.
                - 기본 값은 analyzer
            - search_quote_analyzer
                - 구문 발견 시 검색에 사용해야 하는 분석기 종류.
                - 기본 값은 search_analyzer
            - similarity
                - 어떤 socring 알고리즘이나 similarity를 사용할 지 결정
                - 기본 값은 BM25
            - term_vector
                - 필드에 대해 term vectors를 저장할지 여부
                - 기본 값은 no
            - meta
                - 필드에 대한 metadata
            
            ### Synthetic _source
            
            <aside>
            💡 Synthetic _source는 일반적으로 TSDB 인덱스(index.mode가 time_series로 설정된 index)에 대해서만 사용 가능하다. preview 기술로 제거될 수 있다.
            
            </aside>
            
            - synthetic _source를 지원하는 키워드 하위 필드가 있거나 텍스트 필드가 store를 true로 설정한 경우 텍스트 필드는 synthetic _source를 지원한다.
            - 어느 쪽이든 copy_to가 없을 수도 있다.
            - 하위 키워드 필드를 사용하는 경우 키워드 필드의 값이 정렬되는 것과 동일한 방식으로 값이 정렬된다.
            - 기본적으로 이는 중복 항목이 제거된 상태로 정렬된다.
            - 텍스트 필드는 재정렬 시 구문 및 범위 쿼리에 영향을 미칠 수 있다.
            - text 필드에 store 필드가 true로 설정되면 순서와 중복 항목이 유지된다.
            
            ### fielddata mapping parameter
            
            - text 필드는 기본적으로 검색 가능하지만 기본적으로 집계, 정렬, scripting에는 사용할 수 없다.
            - text 필드에서 script를 사용하여 필드의 값을 정렬, 집계, 액세스하려고 하면 텍스트 필드에서 필드 데이터가 기본적으로 비 활성화되어 있음을 나타내는 예외가 표시된다.
            - 필드 데이터를 메모리에 불러오려면 fielddata=true 필드에 설정해야 한다.
                - 단, 필드 데이터를 메모리에 불러오면 상당한 메모리가 소모될 수 있다
            - 필드 데이터는 집계, 정렬, scripting의 전체 텍스트 필드에서 분석된 토큰에 액세스할 수 있는 유일한 방법이다.
            
            ### Before enabling fielddata
            
            - 필드 데이터는 계산 비용이 많이 들기에 필드 데이터 cache와 함께 heap에 저장된다.
            - 필드 데이터를 계산하면 대기 시간이 급증할 수 있으며, heap 사용량이 늘어나면 클러스터 성능 문제가 발생한다.
            - 텍스트 필드로 더 많은 작업을 수행하려는 대부분의 사용자는 전체 텍스트 검색을 위한 text 필드와 집계를 위한 분석되지 않은 keyword 필드를 모두 보여하여 다중 필드 mapping을 사용한다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "properties": {
                      "my_field": { 
                        "type": "text",
                        "fields": {
                          "keyword": { 
                            "type": "keyword"
                          }
                        }
                      }
                    }
                  }
                }
                ```
                
            
            ### Enabling fielddata on text fields
            
            - update mapping API를 사용하여 기존 text 필드에서 필드 데이터를 활성화할 수 있다.
                
                ```json
                PUT my-index-000001/_mapping
                {
                  "properties": {
                    "my_field": { 
                      "type":     "text",
                      "fielddata": true
                    }
                  }
                }
                ```
                
            
            ### fielddata_frequency_filter mapping parameter
            
            - Fielddata 필터링을 사용하면 메모리에 로드되는 용어 수를 줄여 메모리 사용량을 줄일 수 있다.
            - 용어는 빈도(frequency) 별로 필터링 할 수 있다.
            - 빈도 필터를 사용하면 문서 빈도가 최솟값과 최댓값 사이에 있는 용어만 로드할 수 있다.
            - 이는 절대 숫자 또는 백분율로 표시될 수 있다
            - 빈도는 segment별로 계산되며, 백분율을 segment의 모든 문서가 아닌 필드 값이 있는 문서 수를 기준으로 한다.
            - min_segment_size를 사용하여 segment에 포함해야 하는 최소 문자 수를 지정하면 작은 segment를 완전히 제외할 수 있다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "properties": {
                      "tag": {
                        "type": "text",
                        "fielddata": true,
                        "fielddata_frequency_filter": {
                          "min": 0.001,
                          "max": 0.1,
                          "min_segment_size": 500
                        }
                      }
                    }
                  }
                }
                ```
                
            
            ### Match-only text field type
            
            - 공간 효율성을 위해 위치 쿼리의 scoring 및 효율성을 교환하는 텍스트의 변형이다.
            - 이 필드는 문서(index_options: docs)만 indexing하고 규범(norms: false)를 비 활성화하는 텍스트 필드와 동일한 방식으로 데이터를 효과적으로 저장한다.
            - 용어 쿼리는 텍스트 필드만큼 빠르지는 않더라도 빠르게 수행된다.
            - match_phrase 쿼리와 같은 위치가 필요한 쿼리는 구문이 일치하는지 확인하기 위해 _source 문서를 확인해야 하므로 느리게 수행된다.
            - Analysis는 구성할 수 없다. 텍스트는 항상 기본 분석기를 사용하여 분석된다.
            - span queries가 지원되지 않는다. 대신 interval 쿼리를 사용하거나 span 쿼리가 절대적으로 필요한 경우에는 text 필드 유형을 사용해야 한다.
            - 그 외에는 match_only_text는 텍스트와 동일한 쿼리를 지원한다.
            - 텍스트와 마찬가지로 정렬을 지원하지 않으며 집계에 대한 지원도 제한적이다.
                
                ```json
                PUT logs
                {
                  "mappings": {
                    "properties": {
                      "@timestamp": {
                        "type": "date"
                      },
                      "message": {
                        "type": "match_only_text"
                      }
                    }
                  }
                }
                ```
                
                ### Parameters for match-only text fields
                
                - fields
                    - 다중 필드를 사용하면 검색을 위한 하나의 필드와 정렬 및 집계를 위한 다중 필드, 또는 서로 다른 분석기로 동일한 문자열 값을 분석하는 등 다양한 목적을 위해 여러 방법으로 동일한 문자열 값을 indexing할 수 있다.
                - meta
                    - 필드에 대한 metadata
    - Token count
        
        ### Token count field type
        
        - token_count 유형 필드는 실제로 문자열 값을 받아들이고 분석한 다음 문자열의 토큰 수를 indexing하는 정수 필드다.
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "name": { 
                    "type": "text",
                    "fields": {
                      "length": { 
                        "type":     "token_count",
                        "analyzer": "standard"
                      }
                    }
                  }
                }
              }
            }
            
            PUT my-index-000001/_doc/1
            { "name": "John Smith" }
            
            PUT my-index-000001/_doc/2
            { "name": "Rachel Alice Williams" }
            
            GET my-index-000001/_search
            {
              "query": {
                "term": {
                  "name.length": 3 
                }
              }
            }
            ```
            
        
        ### Parameter for token_count fields
        
        - analyzer
            - 필수 값
            - 문자열 값을 분석하는 데 사용해야 하는 analyzer.
            - 최상의 성능을 위해선 토큰 필터 없이 분석기를 사용해야 한다.
        - enable_position_increments
            - 위치 증분(position increments)을 계산해야 하는지 여부를 나타낸다.
            - 분석기 필터에 의해 제거된 토큰을 계산하지 않으려면 false로 설정해야 한다.
        - doc_values
            - 나중에 정렬, 집계, scripting에 사용할 수 있도록 필드를 column-stride 방식으로 디스크에 저장할지 여부.
            - 기본 값 true
        - index
            - 해당 필드 검색 가능 여부
            - 기본 값 true
        - null_value
            - 명시적인 null 값을 대체하는 필드와 동일한 유형의 숫자 값을 허용한다.
            - 기본 값은 null
        - store
            - 필드 값을 _source 필드와 별도로 저장하고 검색할 수 있는지 여부
    - Unsigned long
        
        ### Unsigned long field type
        
        - Unsigned long은 최솟값이 0이고 최댓값이 0부터 18446744073709551615까지인 부호 없는 64비트 정수를 나타내는 숫자 필드 유형이다.
            
            ```json
            PUT my_index
            {
              "mappings": {
                "properties": {
                  "my_counter": {
                    "type": "unsigned_long"
                  }
                }
              }
            }
            ```
            
            - Unsigned long은 숫자 또는 문자열 형식으로 indexing될 수 있으며 [0, 18446744073709551615] 범위의 정수 값을 나타낸다. 소수 부분은 가질 수 없다.
            - terms 쿼리는 숫자 또는 문자열 형식의 모든 숫자를 허용한다.
            - 범위 검색에는 소수 부분이 포함된 값이 포함될 수 있다. 이 경우 elasticsearch는 이를 정수 값으로 변환한다. gte, gt는 위쪽을 포함한 가장 가까운 정수로, lt, lte는 아래쪽을 포함한 가장 가까운 정수로 변환된다.
            - 정밀도 손실 없이 구문 분석되려면 범위를 문자열로 전달하는 것이 좋다.
                
                ```json
                GET /my_index/_search
                {
                    "query": {
                        "range" : {
                            "my_counter" : {
                                "gte" : "9223372036854775808",
                                "lte" : "18446744073709551615"
                            }
                        }
                    }
                }
                ```
                
            
            ### Sort values
            
            - unsigned_long 필드에 대한 정렬이 포함된 쿼리의 경우 특정 문서에 대해 Elasticsearch는 이 문서의 값이 Long 범위 내에 있으면 long 유형의 정렬 값을 반환하고, 값이 이 범위를 초과하면 BigInteger 유형의 정렬 값을 반환한다.
            - REST 클라이언트에서 이 유형을 올바르게 지원하려면 JSON에서 큰 정수 값을 처리할 수 있어야 한다.
            
            ### Stored fields
            
            - Unsigned_long의 저장 필드는 string으로 저장되고 반환 된다.
            
            ### Aggregations
            
            - terms 집계의 경우 정렬 값과 유사하게 Long 또는 BigInteger 값이 사용된다. 다른 집계의 경우 값은 double 유형으로 변환된다.
            
            ### Script values
            
            - 기본적으로 unsigned_long 필드의 스크립트 값은 Java의 signed Long으로 반환된다. 즉, Long.MAX_VALUE 보다 큰 값은 음수 값으로 표시된다.
            - Long.comapreUnsigned(long, long), Long.divideUnsigned(long, long), Long.remainderUnsigned(long, long)을 사용하여 이러한 값을 올바르게 사용할 수 있다.
            - 혹은 field API를 사용하여 스크립트에서 부호 없는 long 유형을 BigInteger로 처리할 수 있다.
                
                ```json
                "script": {
                    "source": "field('my_counter').asBigInteger(BigInteger.ZERO)"
                }
                ```
                
            
            ### Queries with mixed numeric types
            
            - 정렬이 포함된 쿼리를 제외하고 unsigned_long이 포함된 혼합 숫자 유형을 사용한 검색이 지원된다.
            - 동일한 필드 이름이 한 인덱스에는 unsigned_long 유형이 있고 다른 인덱스에는 long 유형이 있는 두 인덱스에 대한 정렬 쿼리는 올바른 결과를 생성하지 않으므로 피해야 한다.
            - 이러한 종류의 정렬이 필요한 경우에는 스크립트 기반 정렬을 대신 사용할 수 있다.
            - unsigned_long을 포함한 여러 숫자 유형에 대한 집계가 지원된다.
            - 이 경우 값은 double 유형으로 변환된다.
    - Version
        
        ### Version
        
        - Version 필드 유형은 소프트웨어 버전 값을 처리하고 이에 대한 특수한 우선순위 규칙을 지원하기 위한 키워드 필드의 특수화이다.
        - 우선순위는 의미 체계 버전 관리(Semantic Versioning)에 설명된 규칙에 따라 정의된다.
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "my_version": {
                    "type": "version"
                  }
                }
              }
            }
            ```
            
        - Version 필드는 일반 키워드 필드와 동일한 검색 기능을 제공한다.
        - 소프트웨어 버전은 3개 이하의 기본 식별자가 허용된다는 점을 제외하고 의미론적 버전 관리 규칙 스키마와 우선 순위 규칙을 따라야 한다.
            
            ### Parameters for version fields
            
            - meta : 필드에 대한 메타데이터
            
            ### Limitations
            
            - version 필드는 과도한 wildcard, 정규식 또는 fuzzy 검색에 최적화되지 않는다.
            - 이러한 유형의 쿼리가 이 필드에서 작동하는 동안 이러한 종류의 쿼리에 크게 의존하는 경우 일반 keyword 필드 사용을 고려해야 한다.
            
            ### Synthetic _source
            
            <aside>
            💡 Synthetic _source는 일반적으로 TSDB 인덱스(index.mode가 time_series로 설정된 index)에 대해서만 사용 가능하다. preview 기술로 제거될 수 있다.
            
            </aside>
            
            - version 필드는 copy_to를 선언하지 않는 한 Synthetic _source를 지원한다.
            - Synthetic _source는 항상 버전 필드를 정렬하고 중복 항목을 제거한다.
- Metadata fields
    - Metadata fields
        
        ### Metadata fields
        
        - 각 문서에는 _id, _index metadata 필드와 같은 연관된 metadata가 있다. 이러한 metadata 필드 중 일부의 동작은 mapping이 생성될 때 사용자 정의될 수 있다.
            
            ### Identity metadata fields
            
            - index : 문서가 속한 인덱스
            - _id : 문서의 ID
            
            ### Document source metadata fields
            
            - _source : 문서 본문을 나타내는 원본 JSON
            - _size : mapper_size 플러그인에서 제공하는 필드 크기(_source 바이트)
            
            ### Doc count metadata field
            
            - _doc_count : 문서가 사전 집계된 데이터를 나타낼 때 문서 수를 저장하는 데 사용되는 사용자 정의 필드
            
            ### Indexing metadata fields
            
            - _field_names : Null이 아닌 값을 포함하는 문서의 모든 필드
            - _ignored : ignore_malformed로 인해 인덱스 시 무시된 문서의 모든 필드
            
            ### Routing metadata field
            
            - _routing : 문서를 특정 shard로 routing하는 사용자 정의 routing 값
            
            ### Other metadata field
            
            - _meta : Application별 metadata
            - _tier : 문서가 속한 인덱스의 현재 데이터 계층 기본 설정
    - _doc_count fileds
        
        ### _doc_count field
        
        - Bucket 집계는 항상 각 bucket에서 집계되고 분할 된 문서 수를 표시 하는 doc_count 필드를 반환한다.
        - doc_count 값 계산은 각 bucket에 수집된 모든 문서에 대해 1씩 증가한다.
        - 이와 같은 방식은 개별 문서에 대한 집계를 계산할 때 효과적이지만 하나의 요약 필드가 여러 문서를 나타낼 수 있으므로 사전 집계된 데이터를 저장하는 문서는 정확하게 나타내지 못한다.
        - _doc_count metadata 필드 유형은 이런 문제점을 해결하기 위해 도입되었다.
        - _doc_count는 항상 단일 요약 필드에 집계된 문서 수를 나타내는 양의 정수여야 한다.
        - _doc_count 필드가 문서에 추가되면 모든 bucket 집계는 해당 값을 존중하고 필드 값만큼 bucket doc_count를 증가 시킨다.
            - 문서에 _doc_count 필드가 포함되어 있지 않으면 기본적으로 _doc_count = 1이 암시 된다.
            - _doc_count 필드는 문서당 하나의 양의 정수만 저장할 수 있으며 nested 배열은 허용되지 않는다. 문서에 _doc_count 필드가 없으면 aggregators는 1씩 증가하며 이는 기본 동작이다.
    - _field_names fileds
        
        ### _field_names field
        
        - null 이외의 값을 포함하는 문서의 모든 필드 이름을 indexing하는데 사용되는 필드다
        - exists 쿼리에서 특정 필드에 대해 null이 아닌 값이 있거나 없는 문서를 찾는 데 사용되었다.
        - _field_names 필드는 doc_vlaues 및 Norms가 비 활성화된 필드 이름만 indexes한다.
        - doc_values 또는 Norm이 활성화된 필드의 경우 exists 쿼리를 계속 사용할 수 있지만 _field_names 필드는 사용하지 않는다.
        
        ### Disabling _field_names
        
        - _field_names 비활성화는 8.0 이후 더 이상 지원하지 않는다. 이전에 수행했던 index overhead를 더 이상 수행하지 않기 때문에 이제 기본적으로 활성화된다.
    - _ignored fileds
        
        ### _ignored field
        
        - _ignored 필드는 문서가 indexing될 때 무시된 문서의 모든 필드 이름을 indexing하고 저장한다.
        - 이 필드는 term, terms, exists 쿼리로 검색 가능하며 search hits의 일부로 반환 된다.
    - _id fileds
        
        ### _id 필드
        
        - 각 문서에는 고유하게 식별하는 _id가 있으며 이는 GET API 또는 ids 쿼리를 사용하여 문서를 조회할 수 있도록 indexed된다.
        - _id는 indexing시 할당되거나 Elasticsearch에 의해 고유한 _id가 생성될 수 있다.
        - 이 필드는 mapping에서 구성할 수 없다
        - _id 필드의 값은 term, terms, match, query_string과 같은 쿼리에서 액세스할 수 있다.
        - _id필드는 집계, 정렬 및 scripting에 사용이 제한된다.
        - _id 필드에 대한 정렬 또는 집계가 필요한 경우 _id 필드의 내용을 doc_values가 활성화된 다른 필드에 복제하는 것이 권장된다.
        - _id의 크기는 512바이트로 제한되며 더 큰 값은 거부된다.
    - _index fileds
        
        ### _index field
        
        - _index 필드는 문서가 indexed된 인덱스에 대한 일치를 허용한다.
        - 특정 쿼리 및 집계에서, 그리고 정렬 또는 scripting 시 해당 값에 액세스할 수 있다.
        - _index 필드는 가상으로 노출된다. 즉, Lucene 인덱스에 실제 필드로 추가되지 않는다
        - 이는 접두사 및 wildcard 쿼리 뿐만 아니라 terms 쿼리(혹은 match, query_string, simple_query_string 쿼리와 같은 term 쿼리로 다시 작성된 모든 쿼리)에서 _index 필드를 사용할 수 있음을 의미한다.
        - 그러나 정규식 및 fuzzy 쿼리는 지원하지 않는다.
        - _index 필드에 대한 쿼리는 구체적인 인덱스 이름 외에도 인덱스 별칭을 허용한다.
        - cluster_1:index_3 과 같은 원격 인덱스 이름을 지정할 떄 쿼리에는 구분 문자 : 가 포함되어야 한다.
    - _meta fileds
        
        ### _meta field
        
        - mapping 유형에는 이와 관련된 사용자 정의 metadata가 있을 수 있다. 이는 Elasticsearch에서 전혀 사용되지 않지만 문서가 속한 class와 같은 application별 metadata를 저장하는 데 사용할 수 있다.
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "_meta": { 
                  "class": "MyApp::User",
                  "version": {
                    "min": "1.0",
                    "max": "1.3"
                  }
                }
              }
            }
            ```
            
        - update mapping API를 사용하여 기존 유형에서 _meta 필드를 업데이트할 수 있다.
            
            ```json
            PUT my-index-000001/_mapping
            {
              "_meta": {
                "class": "MyApp2::User3",
                "version": {
                  "min": "1.3",
                  "max": "1.5"
                }
              }
            }
            ```
            
    - _routing fileds
        
        ### _routing field
        
        - 문서는 다음 공식을 사용하여 인덱스의 특정 shard로 routing된다.
            - Routing_factor = num_routing_shards / num_primary_shards
            - shard_num = (hash(_routing) % numrouting_shards) / Routing_factor
                - num_routing_shards 는 index.number_of_routing_shards 인덱스 설정의 값이다
                - num_primary_shards는 index.number_of_shards 인덱스 설정의 값이다.
        - 기본 _routing 값은 문서의 _id다.
        - 문서 별로 사용자 정의 routing 값을 지정하여 사용자 정의 routing 패턴을 구현할 수 있다.
            
            ```json
            PUT my-index-000001/_doc/1?routing=user1&refresh=true 
            {
              "title": "This is a document"
            }
            
            GET my-index-000001/_doc/1?routing=user1
            ```
            
        - 필드 값은 routing 쿼리에서 액세스할 수 있다.
            
            ```json
            GET my-index-000001/_search
            {
              "query": {
                "terms": {
                  "_routing": [ "user1" ] 
                }
              }
            }
            ```
            
            - 데이터 stream은 temlate에서 활성화된 설정으로 allow_custom_routing이 생성되지 않은 경우 사용자 지정 routing을 지원하지 않는다.
            
            ### Searching with custom routing
            
            - 사용자 정의 routing을 사용하면 검색의 영향을 줄일 수 있다. 검색 요청을 인덱스의 모든 shard에 fan-out할 필요 없이 특정 routing 값과 일치하는 shard에만 요청을 보낼 수 있다.
            
            ### Making a routing value required
            
            - 사용자 정의 routing을 사용하는 경우 문서를 indexing, getting, deleting, updating할 때마다 routing 값을 제공하는 것이 중요하다.
            - routing 값을 잊어버리면 문서가 둘 이상의 shard에서 indexing될 수 있다.
            - 안전장치로서 모든 CRUD 작업에 필요한 사용자 지정 routing 값을 생성하도록 _routing 필드를 구성할 수 있다.
            
            ### Unique IDs with custom routing
            
            - 사용자 정의 _routing를 지정하여 문서를 indexing할 때 인덱스의 모든 shard에서 _id의 고유성이 보장되지 않는다.
            - 동일한 _id를 가진 문서는 다른 _routing 값으로 indexing된 경우 다른 shard에 포함될 수 있다.
            - ID가 인덱스 전체에서 고유한지 확인하는 것은 사용자의 몫이다.
            
            ### Routing to an index partition
            
            - 사용자 지정 routing값이 단일 shard가 아닌 shard의 하위 집합으로 이동하도록 인덱스를 구성할 수 있다.
            - 이는 불균형 cluster로 끝날 위험을 완화하는 동시에 검색의 영향을 줄이는 데 도움이 된다.
            - 이는 인덱스 생성시 인덱스 level 설정 index.routing_partition_size를 제공하여 수행된다.
            - 파티션 크기가 증가할수록 요청 당 더 많은 shard를 검색해야 하는 대신 데이터가 더욱 균등하게 분산된다.
            - shard 계산 공식은 다음과 같다.
                - routing_value = hash(_routing) + hash(_id) % routing_partition_size
                - shard_num = (routing_value % num_routing_shards) / routing_factor
            - 즉, _routing 필드는 인덱스 내의 shard 세트를 계산하는 데 사용된 다음 _id 해당 세트 내에서 shard를 선택하는 데 사용된다.
            - 이 기능을 활성화하려면 index.routing_partition_size의 값이 1보다 크고 index.number_of_shards보다 작아야 한다.
            - 활성화 시 분할된 인덱스에는 다음 제한 사항이 적용된다.
                - join 필드 관계가 있는 mapping은 생성될 수 없다.
                - 인덱스 내의 모든 mapping에는 필수로 표시된 _routing 필드가 있어야 한다.
    - _source fileds
        
        ### _Source field
        
        - _source 필드에는 인덱스 시 전달된 원본 JSON 문서 본문이 포함되어 있다.
        - _source 필드 자체는 indexing되지 않으므로 검색할 수 없지만 get 또는 search와 같은 가져오기 요청을 실행할 때 반환될 수 있도록 저장된다.
            
            ### Synthetic _Source
            
            <aside>
            💡 Synthetic _source는 일반적으로 TSDB 인덱스(index.mode가 time_series로 설정된 index)에 대해서만 사용 가능하다. preview 기술로 제거될 수 있다.
            
            </aside>
            
            - source 필드는 매우 편리하지만 디스크에서 상당한 양의 공간을 차지한다. 소스 문서를 보낸 그대로 디스크에 저장하는 대신 Elasticsearch는 검색 시 source 콘텐츠를 즉석에서 재구성할 수 있다.
            - _source에서 mode: synthetic을 설정하여 활성화한다.
            - 즉석 재구성은 일반적으로 소스 문서를 그대로 저장하고 쿼리 시 로드하는 것보다 속도가 느리지만 저장 공간을 많이 절약한다.
                
                ```json
                PUT idx
                {
                  "mappings": {
                    "_source": {
                      "mode": "synthetic"
                    }
                  }
                }
                ```
                
            
            ### Synthetic _source restrictions
            
            - Synthetic _source 콘텐츠를 검색하면 원본 JSON과 비교하여 약간의 수정이 이루어진다.
            - 스크립트에선 parmas.source을 사용할 수 없다. 대신 doc API 또는 field API를 사용해야 한다.
            - 스크립트가 없는 런타임 필드와 액세스하는 런타임 필드는 현재 Synthetic _source를 사용하는 인덱스에 대해 지원되지 않는다.
            - 대신 문서 값이나 field API를 사용하여 필드에 액세스하는 scripting된 런타임 필드를 사용해야 한다.
            - Synthetic _source는 다음 필드 유형만 포함하는 인덱스와 함께 사용할 수 있다.
                - aggregate_metric_double, boolean, byte, date, date_nanos, dense_vector, double, flattened, flaot, geo_point, half_float, histogram, integer, ip, keyword, long, scaled_float, short, text, version, wildcard
            
            ### Synthetic _source modification
            
            - Syntetic _cource이 활성화되면 검색된 문서는 원본 JSON과 비교하여 일부 수정된다.
                
                ### Arrays moved to leaf fields
                
                - Syntetic _cource 배열은 leaf로 이동 된다.
                - 이로 인해 일부 배열이 사라질 수 있다.
                
                ### Fields named as they are mapped
                
                - mapping에 이름이 지정되 대로 Synthetic _source 이름 필드가 지정된다. 동적 mapping과 함께 사용하면 이름에 점(.)이 있는 필드는 기본적으로 여러 개체로 해석되는 반면, 필드 이름의 점은 하위 객체가 비 활성화된 객체 내에서 유지된다.
                
                ### Alphabetical sorting
                
                - Synthetic _source 필드는 알파벳순으로 정렬된다.
                - JSON FRC는 Synthetic _source 없이는 원래 순서가 유지되고 일부 application은 사용에 반하여 해당 순서로 작업을 수행할 수 있다.
            
            ### Disabling the _source field
            
            - source 필드는 인덱스 내에서 스토리지 overhead를 발생 시킨다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "_source": {
                      "enabled": false
                    }
                  }
                }
                ```
                
            - 단, _source 필드를 비활성화 할 경우 다음의 기능이 제한된다.
                - update, update_by_query, reindex API
                - 즉석에서 강조 표시된다.
            
            ### Including /Exlcuding fields frm _source
            
            - 전문가 전용 기능은 문서가 색인화된 후 _source 필드가 저장 되기 전에 _source 필드의 내용을 정리하는 기능이다
            - _source에서 필드를 제거하는 것은 _source를 비활성화 하는 것과 유사한 단점이 있다.
    - _tier fileds
        
        ### _tier field
        
        - _tier 필드는 문서가 indexed된 인덱스의 tier_preference 설정에 대한 일치를 허용한다. 기본 값은 특정 쿼리에서 액세스할 수 있다.
            
            ```json
            PUT index_1/_doc/1
            {
              "text": "Document in index 1"
            }
            
            PUT index_2/_doc/2?refresh=true
            {
              "text": "Document in index 2"
            }
            
            GET index_1,index_2/_search
            {
              "query": {
                "terms": {
                  "_tier": ["data_hot", "data_warm"] 
                }
              }
            }
            ```
            
        - match, query_string, term, terms, simple_query_string 쿼리와 같은 용어 쿼리로 다시 작성된 모든 쿼리에서 _tier 필드를 사용할 수 있다.
        - 접두사 및 wildcard 쿼리 역시 _tier 필드를 사용할 수 있다
        - 정규식 및 fuzzy 쿼리는 지원하지 않는다.
        - 인덱스의 tier_preference 설정은 우선 순위에 따라 쉼표로 구분된 계층 이름 목록이다.
        - 즉, 인덱스 호스팅에 선호 되는 계층이 먼저 나열되고 그 뒤에 잠재적으로 많은 대체 옵션이 나열된다.
        - 쿼리 일치에서는 첫 번째 기본 설정(목록의 첫 번째 값)만 고려한다.
- Mapping parameters
    - analyzer
        
        ### analyzer
        
        - text 필드만 analyzer mapping 매개변수를 허용한다.
        - analyzer 매개변수는 text 필드를 indexing하거나 검색할 때 텍스트 분석에 사용되는 분석기를 지정한다.
        - search_analyzer mapping 매개변수로 재 정의되지 않는 한 analzyer는 인덱스 및 검색 분석 모두에 사용된다.
        - update mapping API를 사용하여 기존 필드에서 analyzer 설정을 업데이트할 수 없다.
            
            ### search_quote_analyzer
            
            - search_qoute_analyzer 설정을 사용하면 구문에 대한 분석기를 지정할 수 있다. 이는 구문 쿼리에 대한 중지 단어를 비 활성화하는 작업을 처리할 때 특히 유용하다.
            - 구문에 대한 stop word를 비 활성화하려면 세 가지 분석기 설정을 활용하는 필드가 필요하다
                1. stop words포함한 모든 용어를 indexing하기 위한 analyzer 설정
                2. stop words를 제거하는 구문이 아닌 쿼리에 대한 search_analyzer 설정
                3. stop words를 제거하지 않는 구문 쿼리에 대한 search_quote_analyzer 설정
                    
                    ```json
                    PUT my-index-000001
                    {
                       "settings":{
                          "analysis":{
                             "analyzer":{
                                "my_analyzer":{ 
                                   "type":"custom",
                                   "tokenizer":"standard",
                                   "filter":[
                                      "lowercase"
                                   ]
                                },
                                "my_stop_analyzer":{ 
                                   "type":"custom",
                                   "tokenizer":"standard",
                                   "filter":[
                                      "lowercase",
                                      "english_stop"
                                   ]
                                }
                             },
                             "filter":{
                                "english_stop":{
                                   "type":"stop",
                                   "stopwords":"_english_"
                                }
                             }
                          }
                       },
                       "mappings":{
                           "properties":{
                              "title": {
                                 "type":"text",
                                 "analyzer":"my_analyzer", 
                                 "search_analyzer":"my_stop_analyzer", 
                                 "search_quote_analyzer":"my_analyzer" 
                             }
                          }
                       }
                    }
                    
                    PUT my-index-000001/_doc/1
                    {
                       "title":"The Quick Brown Fox"
                    }
                    
                    PUT my-index-000001/_doc/2
                    {
                       "title":"A Quick Brown Fox"
                    }
                    
                    GET my-index-000001/_search
                    {
                       "query":{
                          "query_string":{
                             "query":"\"the quick brown fox\"" 
                          }
                       }
                    }
                    ```
                    
            - update mapping API를 사용하여 기존 필드에서 search_qoute_analyzer 설정을 업데이트할 수 있다.
    - coerce
        
        ### coerce
        
        - Coercion은 필드의 데이터 유형에 맞게 dirty value을 정리하려고 시도한다.
            - 문자열은 숫자로 강제 변환
            - 부동 소수점은 정수 값에 대해 truncated
        - update mapping API를 사용하여 기존 필드에서 coerce 설정 값을 업데이트할 수 있다.
            
            ### Index-level default
            
            - index.mapping.coerce 설정을 인덱스 수준에서 설정하여 모든 mapping 유형에 걸쳐 전역적으로 강제 변환을 비 활성화할 수 있다.
                
                ```json
                PUT my-index-000001
                {
                  "settings": {
                    "index.mapping.coerce": false
                  },
                  "mappings": {
                    "properties": {
                      "number_one": {
                        "type": "integer",
                        "coerce": true
                      },
                      "number_two": {
                        "type": "integer"
                      }
                    }
                  }
                }
                
                PUT my-index-000001/_doc/1
                { "number_one": "10" } 
                
                PUT my-index-000001/_doc/2
                { "number_two": "10" }
                ```
                
    - copy_to
        
        ### copy_to
        
        - copy_to 매개변수를 사용하면 여러 필드의 값을 그룹 필드로 복사한 다음 단일 필드로 쿼리할 수 있다.
        - 여러 필드를 자주 검색하는 경우 copy_to를 사용하여 더 적은 수의 필드를 검색하면 검색 속도를 향상시킬 수 있다.
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "first_name": {
                    "type": "text",
                    "copy_to": "full_name" 
                  },
                  "last_name": {
                    "type": "text",
                    "copy_to": "full_name" 
                  },
                  "full_name": {
                    "type": "text"
                  }
                }
              }
            }
            
            PUT my-index-000001/_doc/1
            {
              "first_name": "John",
              "last_name": "Smith"
            }
            
            GET my-index-000001/_search
            {
              "query": {
                "match": {
                  "full_name": { 
                    "query": "John Smith",
                    "operator": "and"
                  }
                }
              }
            }
            ```
            
            - 복사되는 필드 값은 용어(분석 프로세스의 결과)가 아니다.
            - 원본 _source 필드는 복사된 값을 표시하도록 수정되지 않는다.
            - “copy_to”:[”field_1”, “field_2”]를 사용하여 동일한 값을 여러 필드에 복사할 수 있다.
            - 중간 필드를 통해 재귀적으로 복사할 수 없다. 대신 원래 필드의 여러 필드에 직접 copy_to를 사용해야 한다.
            - 대상 필드가 인덱스 mapping에 없으면 일반적으로 동적 mapping 동작이 적용된다.
            - dynamic을 true로 설정하면 존재하지 않는 대상 필드가 인덱스 mapping에 동적으로 추가된다.
            - dynamic이 false로 설정된 경우 대상 필드가 인덱스 mapping에 추가되지 않으며 값이 복사 되지 않는다.
            - dynamic이 strict로 설정된 경우 존재하지 않는 필드에 복사하면 오류가 발생한다.
            - 값이 객체 형태를 취하는 필드 유형에는 copy_to가 지원되지 않는다.
    - doc_values
        
        ### doc_values
        
        - doc_values는 문서 인덱스 시간에 구축된 온디스크 데이터 구조로, 문서를 찾아 해당 분야에 있는 용어를 찾을 수 있는 액세스 패턴을 가능하게 한다.
        - _source와 동일한 값을 저장하지만 정렬 및 집계에 훨씬 더 효율적인 열 기반 방식으로 저장된다.
        - Doc 값은 text 및 annotated_text를 제외하고 거의 모든 필드 유형에서 지원된다.
            
            ### doc_value-only fields
            
            - 문서 값 전용 필드는 일반적으로 필터링에 사용되지 않을 것으로 예상되는 필드(Ex; metric data의 gauges나 counters)에 적합하다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "properties": {
                      "status_code": { 
                        "type":  "long"
                      },
                      "session_id": { 
                        "type":  "long",
                        "index": false
                      }
                    }
                  }
                }
                ```
                
            
            ### Disabling doc values
            
            - 문서 값을 지원하는 모든 필드는 기본적으로 활성화되어 있다. 필드를 정렬하거나 집계할 필요가 없거나 스크립트에서 필드 값에 액세스할 필요가 없다고 확신하는 경우 디스크 공간을 절약하기 위해 문서 값을 비활성화할 수 있다.
            - 단, wildcard 필드에 대한 문서 값은 비활성화할 수 없다.
    - dynamic
        
        ### dynamic
        
        - Elasticsearch는 새 필드가 포함된 문서를 인덱싱하면 해당 필드를 문서 또는 문서 내의 내부 객체에 동적으로 추가한다.
            
            ### Setting dynamic on inner objects
            
            - 내부 객체는 상위 객체의 dynamic 설정을 상속한다.
            - type level에서 dynamic mapping이 비활성화 되어있을 경우 새로운 최상위 필드가 동적으로 추가되진 않는다.
            - 그러나 내부 객체는 dynamic mapping을 활성화하므로 내부 객체에 필드를 추가할 수 있다.
            
            ### Parameters for dynamic
            
            - true : 새 필드가 매핑에 추가된다.
            - runtime : 새필드는 런타임 필드로 mapping에 추가된다. 필드는 인덱싱되지 않으며 쿼리 시 _source로부터 로드된다.
            - false : 새 필드는 무시된다. 색인화되거나 검색 가능하지 않지만 반환된 조회수 _source 필드에는 계속 표시된다. mapping에 추가되지 않으며 새 필드를 명시적으로 추가해야 한다.
            - strict : 새 필드가 감지되면 예외가 발생하고 문서가 거부된다. 새 필드는 mapping에 명시적으로 추가되어야 한다.
    - eager_global_ordinals
        
        ### What are global ordinals?
        
        - 키워드와 같은 용어 기반 필드 유형은 보다 간결한 표현을 위해 ordinal mapping을 사용하여 문서 값을 저장한다.
        - ordinal mapping은 사전 편찬 순서에 따라 각 용어에 incremental integer 또는 ordinals를 할당하는 방식으로 작동한다.
        - 필드의 doc 값은 원래 용어 대신 각 문서의 ordinal만 저장하며, ordinal과 용어 간 변환을 위한 별도의 조회 구조를 사용한다.
        - 집계 중 ordinals을 사용하면 성능이 크게 향상될 수 있다.
        - 각 인덱스 segment는 자체 순서 mapping을 정의하지만 집계는 전체 shard에서 데이터를 수집한다. 따라서 집계와 같은 shard 수준 작업에 oridnal을 사용할 수 있도록 Elasitcsearch는 global ordinals라는 통합 mapping을 생성한다.
        - Global ordinals mapping은 segment ordinal 위에 구축되며 각 segment에 대한 global ordinal에서 local ordinal까지의 map을 유지 관리하여 작동한다.
        - 다음 구성 요소가 포함된 경우 global ordinals가 사용된다.
            - Keyword, ip, flattened fields에 대한 특정 bucket 집계.
            - field 데이터를 활성화해야 하는 텍스트 필드의 bucket 집계
            - has_child 쿼리 및 상위 집계를 포함하여 조인 필드의 상위 및 하위 문서에 대한 작업
            
            <aside>
            💡 Global ordinal mapping은 필드 데이터 cache의 일부로 heap 메모리를 사용한다. 높은 cardinality 필드의 집계는 많은 메모리를 사용하고 필드 데이터 회로 차단기를 trigger할 수 있다.
            
            </aside>
            
            ### Loading global ordinals
            
            - 검색 중에 ordinals를 사용하려면 먼저 전역 순서 mapping을 작성해야 한다. 기본적으로 검색 중에 전역 ordinals가 처음 필요할 때 mapping이 로드된다. indexing 속도를 최적화하는 경우에는 올바른 접근 방식이지만, 검색 성능이 우선순위인 경우 집계에 사용될 필드에 전역 oridnals를 적극적으로 로드하는 것이 좋다.
                
                ```json
                PUT my-index-000001/_mapping
                {
                  "properties": {
                    "tags": {
                      "type": "keyword",
                      "eager_global_ordinals": true
                    }
                  }
                }
                ```
                
            - eager_global_ordinals가 활성화되면 shard가 새로 고쳐질 때 global ordinals가 생성된다.
            - 즉시 로딩은 eager_global_ordinals 설정을 업데이트하여 언제든지 비활성화할 수 있다.
                
                ```json
                PUT my-index-000001/_mapping
                {
                  "properties": {
                    "tags": {
                      "type": "keyword",
                      "eager_global_ordinals": false
                    }
                  }
                }
                ```
                
            
            ### Avoiding global ordinal loading
            
            - 일반적으로 전역 서수는 로딩 시간과 메모리 사용량 측면에서 큰 overhead를 나타내지 않는다. 그러나 shard가 큰 인덱스나 필드에 고유한 용어 값이 많이 포함된 경우 전역 서수를 로드하는 데 비용이 많이 들 수 있다.
            - 전역 서수는 shard의 모든 segment에 대한 통합 mapping을 제공하므로 새 segment가 표시되면 완전히 다시 작성해야 한다.
    - enabled
        
        ### enabled
        
        - 최상위 mapping 정의와 객체 필드에만 적용할 수 있는 enabled 설정으로 인해 Elasticsearch는 필드 내용의 구문 분석을 완전히 건너뛸 수 있다.
        - JSON은 _source 필드에서 계속 검색할 수 있지만 다른 방법으로는 검색하거나 저장할 수 없다.
        - 전체 mapping도 비활성화될 수 있다. 이 경우 문서는 _source 필드에 저장된다. 즉, 검색할 수는 있지만 해당 콘텐츠는 어떤 방식으로도 indexed되지 않는다.
        - 기존 필드 및 최상위 mapping 정의에 대한 enabled 설정은 변경할 수 없다.
        - Elasticsearch는 필드 내용 구문 분석을 완전히 건너뛰기 때문에 비활성화된 필드에 객체가 아닌 데이터를 추가할 수 있다.
    - format
        
        ### format
        
        - JSON 문서에서 날짜는 문자열로 표시된다. Elasticsearch는 사전 구성된 형식 세트를 사용하여 문자열을 UTC 기준으로 밀리초를 나타내는 긴 값으로 인식하고 구문 분석한다.
        - 내장된 형식 외에도 yyyy/MM/dd 같은 구문을 사용하여 사용자 정의 형식을 지정할 수 있다.
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "date": {
                    "type":   "date",
                    "format": "yyyy-MM-dd"
                  }
                }
              }
            }
            ```
            
        - 날짜 값을 지원하는 많은 API는 now-1m/d 와 같은 날짜 수학 표현식도 지원한다.
            
            ### Built In Formats
            
            - Built In Formats의 대부분은 엄격한 동반 형식을 가지고 있다.
            - 이를 사용하려면 날짜 형식 이름 앞에 strict_를 추가해야 한다.
                - epoch_millis
                    - epoch 이후 밀리초 수에 대한 formatter.
                    - Java Long.MIN_VALUE, Long.MAX_VALUE 제한이 적용된다.
                - epoch_second
                    - epoch 이후 초에 대한 formatter.
                    - Java Long.MIN_VALUE와 Long 제한이 적용된다.
                    - MAX_VALUE를 1000(초당 밀리초 수)으로 나눈 값이다.
                - date_optional_time, strict_date_optional_time
                    - 날짜에 최소한 연도와 시간이 포함되어야 하는 일반 ISO 날짜/시간 구문 분석기는 선택 사항이다.
                - strict_date_optional_time_nanos
                    - 날짜에 최소한 연도와 시간이 포함되어야 하는 일반 ISO 날짜/시간 구문 분석기는 선택 사항이다.
                    - 두 번째 부분의 일부는 나노초 해상도를 갖는다.
                - basic_date
                    - 4자리 연도, 2자리 월, 2자리 일로 전체 날짜에 대한 기본 formatter.
                - basic_date_time
                    - T : 로 구분된 기본 날짜와 시간을 결합하는 기본 formatter.
                - basic_date_time_no_millis
                    - T : 로 구분된 기본 날짜와 시간을 밀리초 없이 결합하는 기본 formatter.
                - basic_ordinal_date
                    - 4자리 연도와 3자리 dayOfYear를 사용하는 전체 ordinal 날짜에 대한 formatter.
                - basic_ordinal_date_time
                    - 4자리 연도와 3자리 dayOfYear를 사용하는 전체 ordinal 날짜 및 시간에 대한 formatter.
                - basic_ordinal_date_time_no_millis
                    - 4자리 연도와 3자리 dayOfYear를 사용하여 밀리초가 없는 전체 ordinal 날짜 및 시간에 대한 formatter.
                - basic_time
                    - 두 자리 시간, 두 자리 분, 두 자리 초, 세 자리 밀리 및 시간대 offset에 대한 기본 formatter
                - basic_time_no_millis
                    - 두 자리 시간, 두 자리 분, 두 자리 초 및 시간대 offset에 대한 기본 formatter
                - basic_t_time
                    - 두 자리 시간, 두 자리 분, 두 자리 초, 세 자리 밀리초 및 T: 접두사가 붙은 시간대 offset에 대한 기본 formatter
                - basic_t_time_no_millis
                    - 두 자리 시간, 두 자리 분, 두 자리 초, T: 접두사가 붙은 시간대 offset에 대한 기본 fomatter
                - basic_week_date, strict_basic_week_date
                    - 4자리 주 연도, 2자리 주 연도, 1자리 요일로 전체 날짜에 대한 기본 formatter
                - basic_week_date_time, strict_basic_week_date_time
                    - 기본 요일 및 시간을 T: 로 구분하여 결합하는 기본 formatter
                - basic_week_date_time_no_millis, strict_basic_week_date_time_no_millis
                    - 기본 요일 날짜와 시간을 밀리초 없이 T: 로 구분하여 결합하는 기본 fommater
                - date, strict_date
                    - 4자리 연도, 2자리 월, 2자리 날짜로 전체 날짜에 대한 formatter
                - date_hour, strict_date_hour
                    - 전체 날짜, 두 자리 시간을 결합하는 formatter
                - date_hour_minute, strict_date_hour_minute
                    - 전체 날짜, 두 자리 시간, 두 자리 분을 결합하는 formatter
                - date_hour_minute_second, strict_date_hour_minute_second
                    - 전체 날짜, 두 자리 시간, 두 자리 분, 두 자리 초를 결합하는 formatter
                - date_hour_minute_second_fraction, strict_date_hour_minute_second_fraction
                    - 전체 날짜, 두 자리 시간, 두 자리 분, 두 자리 초, 세 자리 분수를 결합하는 formatter
                - date_hour_minute_second_millis, strict_date_hour_minute_second_millis
                    - 전체 날짜, 두 자리 시간, 두 자리 분, 두 자리 초, 세 자리 분수를 결합하는 formatter
                - date_time, strict_date_time
                    - T: 로 구분된 전체 날짜와 시간을 결합하는 fromatter
                - date_time_no_millis, strict_date_time_no_millis
                    - T: 로 구분된 전체 날짜와 시간을 밀리초 없이 결합하는 formatter
                - hour, strict_hour
                    - 하루 중 두 자리 시간에 대한 formatter
                - hour_minute, strict_hour_minute
                    - 두 자리 시간과 두 자리 분에 대한 fromatter
                - hour_minute_second, strict_hour_minute_second
                    - 두 자리 시간, 두 자리 분, 두 자리 초에 대한 formatter
                - hour_minute_second_fraction, strict_hour_minute_second_fraction
                    - 두 자리 시간, 두 자리 분, 두 자리 초, 세 자리 분수에 대한 formatter
                - hour_minute_second_millis, strict_hour_minute_second_millis
                    - 두 자리 시간, 두 자리 분, 두 자리 초, 세 자리 분수에 대한 formatter
                - ordinal_date, strict_ordinal_date
                    - 4자리 연도와 3자리 dayOfYear를 사용하는 전체 서수 날짜에 대한 formatter
                - ordinal_date_time, strict_ordinal_date_time
                    - 4자리 연도와 3자리 dayOfYear를 사용하는 전체 서수 날짜 및 시간에 대한 formatter
                - ordinal_date_time_no_millis,  strict_ordinal_date_time_no_millis
                    - 4자리 연도와 3자리 dayOfYear를 사용하여 밀리초가 없는 전체 서수 날짜 및 시간에 대한 formatter
                - time, strict_time
                    - 두 자리 시간, 두 자리 분, 두 자리 초, 세 자리 분수 및 시간대 offset에 대한 formatter
                - time_no_millis, strict_time_no_millis
                    - 두 자리 시간, 두 자리 분, 두 자리 초 및 시간대 offset에 대한 formatter
                - t_time, strict_t_time
                    - 두 자리 시간, 두 자리 분, 두 자리 초, 세 자리 소수 및 T : 접두사가 붙은 시간대 offset에 대한 formatter
                - t_time_no_millis, strict_t_time_no_millis
                    - 두 자리 시간, 두 자리 분, 두 자리 초, T : 접두사가 붙은 시간대 offset에 대한 formatter
                - week_date, strict_week_date
                    - 4자리 주 연도, 2자리 주 연도, 1자리 요일로 전체 날짜에 대한 formatter
                - week_date_time, strict_week_date_time
                    - T: 로 구분된 전체 요일 및 시간을 결합하는 formatter
                - week_date_time_no_millis, strict_week_date_time_no_millis
                    - T: 로 구분하여 밀리초 없이 전체 요일 및 시간을 결합하는 formatter
                - weekyear, strict_weekyear
                    - 4자리 주 연도에 대한 formatter
                - weekyear_week, strict_weekyear_week
                    - 4자리 주 연도 및 2자리 주 연도에 대한 formatter
                - weekyear_week_day, strict_weekyear_week_day
                    - 4자리 주 연도, 2자리 주 연도, 1자리 요일에 대한 formatter
                - year, strict_year
                    - 4자리 연도에 대한 formatter
                - year_month, strict_year_month
                    - 4자리 연도와 2자리 월에 대한 formatter
                - year_month_day, strict_year_month_day
                    - 4자리 연도, 2자리 월, 2자리 일에 대한 formatter
    - ignore_above
        
        ### ignore_above
        
        - ignore_above 설정보다 긴 문자열은 인덱스가 생성되거나 저장되지 않는다.
        - 문자열 배열의 경우 ignore_above는 각 배열 요소에 개별적으로 적용되며, ignore_above 보다 긴 문자열 요소는 indexing되거나 저장되지 않는다.
        - _source 필드가 활성화된 경우 모든 문자열/배열 요소는 여전히 _source 필드에 존재한다.
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "message": {
                    "type": "keyword",
                    "ignore_above": 20 
                  }
                }
              }
            }
            
            PUT my-index-000001/_doc/1 
            {
              "message": "Syntax error"
            }
            
            PUT my-index-000001/_doc/2 
            {
              "message": "Syntax error with some long stacktrace"
            }
            
            GET my-index-000001/_search 
            {
              "aggs": {
                "messages": {
                  "terms": {
                    "field": "message"
                  }
                }
              }
            }
            ```
            
    - ignore_malformed
        
        ### ignore_malformed
        
        - 필드에 잘못된 데이터 유형을 indexing하려고 하면 기본적으로 예외가 발생하고 전체 문서가 거부된다.
        - ignore_malformed 매개변수를 true로 설정하면 예외를 무시할 수 있다.
        - 잘못된 필드는 indexing되지 않지만 문서의 다른 필드는 정상적으로 처리된다.
        - ignore_malformed 설정은 다음 mapping 유형에서 지원된다.
            - Numeric
                - long, integer, short, byte, double, float, half_float, scaled_float
            - Boolean
                - boolean
            - Date
                - date
            - Date nanoseconds
                - date_nanos
            - Geopoint
                - geo_point 위도/경도의 경우
            - Geoshape
                - geo_shape 복잡한 모양의 경우
            - IP
                - ip IPv4 및 IPv6 주소의 경우
            
            ### Index-level default
            
            - index.mapping.ignore_malformed 설정은 허용되는 모든 mapping 유형에서 전체적으로 잘못된 형식의 콘텐츠를 무시하도록 인덱스 수준에서 설정할 수 있다.
            - 설정을 지원하지 않는 mapping 유형은 인덱스 수준에서 설정된 경우 해당 설정을 무시한다.
            
            ### Dealing with malformed fields
            
            - ignore_malformed가 true인 경우 indexing 시 잘못된 형식의 필드가 자동으로 무시된다.
            - 가능할 때마다 잘못된 형식의 필드가 포함된 문서 수를 유지하지 않으면 필드에 대한 쿼리가 의미 없게 된다.
            
            ### Limits for JSON objects
            
            - ignore_malformed는 다음 데이터 유형에는 사용할 수 없다.
                - Nested data type
                - Object data type
                - Range data types
            - ignore_malformed를 사용하여 잘못된 데이터 유형의 필드에 제출된 JSON 객체를 무시하는 데 사용할 수 없다.
            - 지원되지 않는 필드에 JSON 객체를 제출하면 Elasticsearch는 오류를 반환하고 ignore_malformed 설정에 관계없이 전체 문서를 거부한다.
    - index
        
        ### index
        
        - index 옵션은 필드 값의 indexing 여부를 제어한다.
        - true 또는 false를 허용하며 기본 값은 true
        - 필드를 indexing하면 필드를 효율적으로 쿼리할 수 있는 데이터 구조가 생성된다.
        - Numeric types, date types, boolean type, ip type, geo_point type, keyword type은 indexed되지 않았지만 문서 값만 활성화된 경우에도 쿼리할 수 있다.
        - 인덱스를 전체 스캔해야 하므로 이러한 필드에 대한 쿼리는 느리다.
    - index_options
        
        ### index-options
        
        - index_options 매개변수는 검색 및 강조 표시 목적으로 반전된 인덱스에 추가되는 정보를 제어한다. text, keyword와 같은 용어 기반 필드 유형만 지원한다.
        - 매개변수는 아래와 같다. 각 값은 이전 값을 포함한다.
        - docs
            - 문서 번호만 indexed된다.
        - freqs
            - 문서 번호와 용어 빈도가 indexed 되어 있다. 용어 빈도는 단일 용어보다 반복 용어의 점수를 더 높게 매기는 데 사용된다.
        - positions (default)
            - 문서 번호, 용어 빈도, 용어 위치(또는 순서)가 indexed된다.
        - offsets
            - 문서 번호, 용어 빈도, 위치, 시작 및 끝 문자 offsets이 indexed된다.
    - index_phrases
        
        ### index_phrases
        
        - 활성화된 경우 두 단어로 구성된 단어 조합(shingles)이 별도의 필드에 indexed된다.
        - 이를 통해 더 큰 인덱스를 희생하면서 정확한 구문 쿼리(no slop)를 보다 효율적으로 실행할 수 있다.
        - 불용어가 포함된 문구는 보조 필드를 사용하지 않고 표준 문구 쿼리로 돌아가기 때문에 이는 불용어가 제거되지 않을 때 가장 잘 작동한다.
        - true 또는 false(default)를 허용한다.
    - index_prefixes
        
        ### index_prefixes
        
        - index-prefixes 매개변수를 사용하면 접두어 검색 속도를 높이기 위해 용어 접두어의 indexing을 활성화할 수 있다. 설정은 다음과 같다.
            - min_chars :
                - 인덱스를 생성할 최소 접두사 길이.
                - 0보다 커야 하며 기본 값은 2
            - max_cahrs
                - 인덱스를 생성할 최대 접두사 길이
                - 20보다 작아야 하며 기본 값은 5
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "properties": {
                      "full_name": {
                        "type": "text",
                        "index_prefixes": {
                          "min_chars" : 1,
                          "max_chars" : 10
                        }
                      }
                    }
                  }
                }
                ```
                
    - meta
        
        ### meta
        
        - 필드에 첨부된 metadata.
        - metadata는 Elasticsearch에 불투명하며, 단위와 같은 필드에 대한 meta 정보를 공유하기 위해 동일한 인덱스에서 작동하는 여러 애플리케이션에만 유용하다.
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "latency": {
                    "type": "long",
                    "meta": {
                      "unit": "ms"
                    }
                  }
                }
              }
            }
            ```
            
        - 필드 metadata는 최대 5개의 항목을 적용하며, 키의 길이는 20보다 작거나 같고 값은 길이가 50보다 작거나 같은 문자열
        - 필드 metadata는 mapping update를 제출하여 업데이트할 수 있다. 업데이트의 metadata는 기존 필드의 metadata를 재정의한다.
        - 필드 metadata는 개체 또는 중첩 필드에서 지원되지 않는다.
        - 표준 metadata 항목은 다음과 같다.
            - unit
                - 숫자 필드와 연관된 단위 : percent, byte, time unit.
                - 숫자 필드에만 유효하다.
            - metric_type
                - 숫자 필드의 측정 항목 유형 : gauge, counter.
                - 숫자 필드에만 유효하다.
    - fields
        
        ### fields
        
        - 다양한 목적을 위해 다양한 방식으로 동일한 필드를 indexed 하는 것이 다중 필드의 목적이다.
        
        ```json
        PUT my-index-000001
        {
          "mappings": {
            "properties": {
              "city": {
                "type": "text",
                "fields": {
                  "raw": { 
                    "type":  "keyword"
                  }
                }
              }
            }
          }
        }
        
        PUT my-index-000001/_doc/1
        {
          "city": "New York"
        }
        
        PUT my-index-000001/_doc/2
        {
          "city": "York"
        }
        
        GET my-index-000001/_search
        {
          "query": {
            "match": {
              "city": "york" 
            }
          },
          "sort": {
            "city.raw": "asc" 
          },
          "aggs": {
            "Cities": {
              "terms": {
                "field": "city.raw" 
              }
            }
          }
        }
        ```
        
        - 다중 필드를 추가할 때 인덱스(또는 datastream)에 문서가 포함된 경우 해당 문서에는 새 다중 필드에 대한 값이 없다. 쿼리 API를 통한 업데이트로 새로운 다중 필드를 채울 수 있다.
        - 다중 필드 mapping은 상위 필드 mapping과 별개다. 다중 필드는 상위 필드에서 mapping 옵션을 상속하지 않는다. 다중 필드는 원래 _source 필드를 변경하지 않는다.
            
            ### Multi-fields with multiple analyzers
            
            - 다중 필드의 또 다른 사용 사례는 더 나은 관련성을 위해 동일한 필드를 다양한 방식으로 분석하는 것이다.
    - normalizer
        
        ### normalizer
        
        - keyword 필드의 normalizer 속성은 분석 체인이 단일 토큰을 생성한다는 점을 제외하면 분석기와 유사하다.
        - normalizer는 키워드를 indexing하기 전뿐만 아니라 검색 시 일치 쿼리와 같은 쿼리 parser나 용어 쿼리와 같은 용어 수준 쿼리를 통해 키워드 필드를 검색하는 경우에도 적용된다.
    - norms
        
        ### norms
        
        - norms는 쿼리에 상대적으로 문서의 점수를 계산하기 위해 나중에 사용되는 다양한 정규화 요소를 저장한다.
        - scoring에는 유용하지만 norms에는 상당히 많은 디스크가 필요하다.
        - 특정 필드에 점수를 매길 필요가 없다면 해당 필드에 대한 norms를 비 활성화해야 한다.
        - Update Mapping API를 사용하여 기존 필드에서 norms를 비 활성화할 수 있다.
        
        ```json
        PUT my-index-000001/_mapping
        {
          "properties": {
            "title": {
              "type": "text",
              "norms": false
            }
          }
        }
        ```
        
    - null_value
        
        ### null_value
        
        - Null 값은 indexed 하거나 검색할 수 없다.
        - 필드가 null로 설정된 경우 해당 필드에 값이 없는 것처럼 처리된다.
        - null_value 매개변수를 사용하면 명시적 null 값을 지정된 값으로 대체하여 indexed하고 검색할 수 있다.
    - position_increment_gap
        
        ### position_increment_gap
        
        - 분석된 텍스트 필드는 근접성 또는 구문 쿼리를 지원할 수 있도록 용어 위치를 고려한다.
        - 여러 값이 있는 text 필드를 indexed할 때 대부분의 구문 쿼리가 값 전체에서 일치하는 것을 방지하기 위해 값 사이에 가짜 간격이 추가된다. 이 간격의 크기는 position_increment_gap을 사용하여 구성되며 기본 값은 100이다.
    - properties
        
        ### properties
        
        - 유형 매핑, 개체 필드 및 중첩 필드에는 속성이라는 하위 필드가 포함되어 있다.
        - 이러한 속성은 객체 및 중첩을 포함한 모든 데이터 유형이 될 수 있다.
            - 인덱스를 생성할 때 명시적으로 정의한다
            - Update Mapping API를 사용하여 mapping 유형을 추가하거나 업데이트할 때 이를 명시적으로 정의한다.
            - 새 필드가 포함된 문서를 indexed하면 동적으로 가능하다.
            
            ### Dot notation
            
            - 쿼리, 집계 등에서 점 표기법을 사용하여 내부 필드를 참조할 수 있다.
            
            ```json
            GET my-index-000001/_search
            {
              "query": {
                "match": {
                  "manager.name": "Alice White"
                }
              },
              "aggs": {
                "Employees": {
                  "nested": {
                    "path": "employees"
                  },
                  "aggs": {
                    "Employee Ages": {
                      "histogram": {
                        "field": "employees.age",
                        "interval": 5
                      }
                    }
                  }
                }
              }
            }
            ```
            
    - search_analyzer
        
        ### search_analyzer
        
        - 일반적으로 쿼리의 용어가 반전된 인덱스의 용어와 동일한 형식인지 확인하기 위해 인덱스와 검색 시 동일한 분석기를 적용해야 한다.
        - 기본적으로 쿼리는 필드 mapping에 정의된 분석기를 사용하지만 이는 search_analyzer 설정으로 재정의 될 수 있다.
    - similarity
        
        ### similarity
        
        - Elasticsearch를 사용하여 필드 별 텍스트 채점 알고리즘이나 유사성(similarity)를 구성할 수 있다.
        - 유사성 설정은 boolean과 같은 기본 BM25 이외의 텍스트 유사성 알고리즘을 선택하는 간단한 방법을 제공한다.
        - text 및 keyword 같은 텍스트 기반 필드 유형만 지원한다.
        - 내장된 유사성의 매개변수를 조정하여 사용자 정의 유사성을 구성할 수 있다.
        - 추가 구성 없이 바로 사용할 수 있는 유사성은 다음과 같다.
            - BM25
                - Okapi BM25 알고리즘. Elasticsearch와 Lucene에서 기본적으로 사용되는 알고리즘이다.
            - boolean
                - 전체 텍스트 순위가 필요하지 않고 쿼리 용어가 일치하는지 여부 만으로 점수를 매겨야 하는 경우 사용되는 간단한 booelan 유사성이다.
                - boolean 유사성은 용어에 대한 쿼리 증가와 동일한 점수를 제공한다.
    - store
        
        ### store
        
        - 기본적으로 필드 값은 검색 가능하도록 indexed되지만 저장되지는 않는다. 즉, 필드를 쿼리할 수 있지만 원래 필드 값을 검색할 수는 없다.
        - 일반적으로는 필드 값은 이미 기본적으로 저장되는 _source 필드의 일부이며, 전체 _source 대신 단일 필드 또는 일부 필드의 값만 검색하려는 경우 source filtering을 사용하여 수행할 수 있기에 중요하지 않다.
        - 단, 제목, 날짜 및 매우 큰 콘텐츠 필드가 있는 문서가 있는 경우 큰 _source 필드에서 해당 필드를 추출하지 않고 제목과 날짜만 검색할 경우에는 필드를 저장하는 것이 합리적일 수 있다.
        
        ```json
        PUT my-index-000001
        {
          "mappings": {
            "properties": {
              "title": {
                "type": "text",
                "store": true 
              },
              "date": {
                "type": "date",
                "store": true 
              },
              "content": {
                "type": "text"
              }
            }
          }
        }
        
        PUT my-index-000001/_doc/1
        {
          "title":   "Some short title",
          "date":    "2015-01-01",
          "content": "A very long content field..."
        }
        
        GET my-index-000001/_search
        {
          "stored_fields": [ "title", "date" ] 
        }
        ```
        
    - subobjects
        
        ### subobjects
        
        - Elasticsearch는 문서를 indexing하거나 mapping을 업데이트할 때 이름에 점이 포함된 필드를 허용하며 해당 필드는 해당 객체 구조로 확장된다.
        - 예를 들어, metrics.time.max 필드는 상위 metrics 객체에 속하는 상위 시간 객체가 있는 최대 leaf 필드로 mapping된다.
        - 위 기본 동작은 대부분의 시나리오에 적합하지만, metric 데이터를 indexing할 때 일반적으로 발생하는 metrics.time 필드에 값이 있는 특정 상황에서는 문제가 발생한다.
        - metric.time.max 및 metrics.time 모두에 대한 값을 보유하는 문서는 시간이 값을 보유하는 leaf 필드이자 최대 하위 필드를 보유하는 객체여야 한다는 점을 고려하여 거부된다.
        - 최상위 mapping 정의 및 객체 필드에만 적용할 수 있는 하위 객체 설정은 객체가 추가 하위 객체를 보유하는 기능을 비활성화하고 필드 이름에 점이 포함되고 공통 접두사를 공유하는 문서를 저장할 수 있게 한다.
            
            ```json
            PUT my-index-000001
            {
              "mappings": {
                "properties": {
                  "metrics": {
                    "type":  "object",
                    "subobjects": false 
                  }
                }
              }
            }
            
            PUT my-index-000001/_doc/metric_1
            {
              "metrics.time" : 100, 
              "metrics.time.min" : 10,
              "metrics.time.max" : 900
            }
            
            PUT my-index-000001/_doc/metric_2
            {
              "metrics" : {
                "time" : 100, 
                "time.min" : 10,
                "time.max" : 900
              }
            }
            
            GET my-index-000001/_mapping
            ```
            
        - 전체 mapping은 하위 객체도 지원하지 않도록 구성할 수 있으며, 이 경우 문서는 leaf 하위 필드만 보유할 수 있다.
            
            ```json
            {
              "my-index-000001" : {
                "mappings" : {
                  "properties" : {
                    "metrics" : {
                      "subobjects" : false,
                      "properties" : {
                        "time" : {
                          "type" : "long"
                        },
                        "time.min" : { 
                          "type" : "long"
                        },
                        "time.max" : {
                          "type" : "long"
                        }
                      }
                    }
                  }
                }
              }
            }
            ```
            
        - 기존 필드 및 최상위 mapping 정의에 대한 subobjects 설정은 변경될 수 없다.
    - term_vector
        
        ### term_vector
        
        - 용어 벡터(term_vector)에는 다음을 포함하여 분석 프로세스에서 생성된 용어에 대한 정보가 포함된다.
            - 용어 목록
            - 각 용어의 위치(또는 순서)
            - 용어를 원래 문자열의 원점에 mapping하는 시작 및 끝 문자 offset
            - payloads(사용 가능한 경우) - 각 용어 위치와 관련된 사용자 정의 binary 데이터
        - 용어 벡터는 특정 문서에 대해 검색할 수 있도록 저장될 수 있다.
        - 용어 벡터에서 허용되는 설정은 다음과 같다.
            - no : 용어 벡터는 저장되지 않는다. (default)
            - yes : 해당 필드의 용어만 저장된다.
            - with_positions : 조건과 위치가 저장된다.
            - with_offsets : 용어 및 문자 offset이 저장된다.
            - with_positions_offsets : 용어, 위치 및 문자 offset이 저장된다.
            - with_positions_payloads : 용어, 위치 및 payload가 저장된다.
            - with_positions_offsets_payloads : 용어, 위치, offset 및 payload가 저장된다.
        - Fast vector highlighter에는 with_positions_offsets이 필요하다. term vectors API는 저장된 모든 것을 검색할 수 있다.
        - 단, with_positions_offsets 설정을 하면 필드 인덱스 크기가 두 배로 늘어난다.
        
        ```json
        PUT my-index-000001
        {
          "mappings": {
            "properties": {
              "text": {
                "type":        "text",
                "term_vector": "with_positions_offsets"
              }
            }
          }
        }
        
        PUT my-index-000001/_doc/1
        {
          "text": "Quick brown fox"
        }
        
        GET my-index-000001/_search
        {
          "query": {
            "match": {
              "text": "brown fox"
            }
          },
          "highlight": {
            "fields": {
              "text": {} 
            }
          }
        }
        ```
        
- Mapping limit settings
    
    ### Mapping limit settings
    
    - 아래의 설정을 사용하여 필드 mapping(수동 또는 동적으로 생성된) 수를 제한하고 문서에서 mapping expolsion이 발생하는 것을 방지할 수 있다.
        - index.mapping.total_fields.limit
            - 인덱스의 최대 필드 수.
            - 필드 및 객체 mapping은 물론 필드 별칭도 이 제한에 포함된다.
            - mapping된 runtime 필드도 이 제한에 포함된다.
            - 기본 값은 1000.
        - index.mapping.depth.limit
            - 내부 객체의 수로 측정 되는 필드의 최대 깊이
            - 모든 필드가 루트 객체 수준에서 정의된 경우 깊이는 1이다.
            - 객체 mapping이 하나 있으면 깊이는 2
            - 기본 값은 20
        - index.mapping.nested_fields.limit
            - nested 인덱스의 최대 개별 mapping 수.
            - nested 유형은 객체 배열을 서로 독립적으로 쿼리 해야 하는 특수한 경우에만 사용해야 한다.
            - 잘못 설계된 mapping을 방지하기 위해 이 설정은 인덱스당 nested 고유 유형 수를 제한한다.
            - 기본 값은 50
        - index.mapping.nested_objects.limit
            - 단일 문서가 모든 nested 유형에 걸쳐 포함할 수 있는 중첩 JSON 객체의 최대 수
            - 문서에 중첩된 객체가 너무 많을 때 메모리 부족 오류를 방지하는 데 도움이 된다.
            - 기본 값은 10000
        - index.mapping.field_name_length.limit
            - 필드 이름의 최대 길이에 대한 설정.
            - 실제로 mapping explosion을 해결하는 것은 아니지만 필드 길이를 제한하는 경우 유용하다
            - 기본 값은 Long.MAX_VALUE(제한 없음)이다.
        - index.mapping.dimension_fields.limit
            - dynamic, Integer
            - 인덱스에 대한 time series dimensions의 최대 수
            - 기본 값은 21
- Removal of mapping types
    
    ### Removal of mapping types
    
    - 8.0.0 이후 mapping tpyes는 지원되지 않는다.

# Text analysis

- Text analysis
    
    ### Text analysis
    
    - 텍스트 분석은 이메일 본문이나 제품 설명과 같은 구조화되지 않은 텍스트를 검색에 최적화된 구조화된 형식으로 변환하는 프로세스다.
    - text 필드를 사용하거나 텍스트 검색이 예상대로 결과를 반환하지 않는 경우 text analaysis를 구성하면 도움이 될 수 있다.
    - Elasticsearch를 사용하여 다음을 수행하는 경우 분석 구성도 조사해야 한다
        - 검색 엔진 구축
        - 구조화되지 않은 데이터 마이닝
        - 특정 언어에 대한 검색 세부 조정
        - 사전 편찬 또는 언어 연구 수행
- Overview
    
    ### Text analysis Overview
    
    - Elasticsearch는 텍스트 분석을 통해 전체 텍스트 검색을 수행할 수 있으며, 여기서 검색은 정확한 일치가 아닌 모든 관련 결과를 반환한다.
        
        ### Tokenization
        
        - 분석기는 토큰화를 통해 전체 텍스트 검색이 가능해진다.
        - 대부분의 경우 토큰은 개별 단어다.
        - 구문을 토큰화하고 각 단어를 별도로 indexed하면 쿼리 문자열의 용어를 개별적으로 조회할 수 있다.
        
        ### Normalization
        
        - 토큰화를 사용하면 개별 용어에 대한 일치가 가능하지만 각 토큰은 여전히 문자 그대로 일치된다. 이는 다음을 의미한다.
            - Quick과 quick이 일치하지 않음
            - fox와 foxes가 일치하지 않음
            - jumps와 leaps가 일치하지 않음 (동의어)
        - 위와 같은 문제를 해결하기 위해 Text analysis를 통해 토큰을 표준 형식으로 정규화할 수 있다.
        - 이를 통해 검색어와 완전히 동일하지는 않지만 여전히 관련성이 있을 만큼 유사한 토큰을 일치시킬 수 있다.
        - 검색어가 의도한 대로 일치하는지 확인하려면 동일한 토큰화 및 정규화 규칙을 쿼리 문자열에 적용하면 확인 가능하다.
        
        ### Customize text analysis
        
        - 텍스트 분석은 전체 프로세스를 관리하는 일련의 규칙인 Analyzer에 의해 수행된다.
        - 검색 환경을 맞춤화하려면 내장된 다른 분석기를 선택하거나 맞춤 분석기를 구성할 수도 있다. 맞춤형 분석기를 사용할 경우 다음을 포함하여 분석 프로세스의 각 단계를 제어할 수 있다.
            - 토큰화 전 텍스트 변경 사항
            - 텍스트가 토큰으로 변환되는 방법
            - indexing 또는 검색 전에 토큰에 대한 정규화 변경 사항
- Concepts
    - Anatomy of an analyzer
        
        ### Anatomy of an analyzer
        
        - 분석기는 문자 필터, 토크나이저, 토큰 필터라는 세 가지 하위 수준 구성 요소를 포함하는 패키지다.
        - 기본 제공 분석기는 이러한 기본 요소를 다양한 언어 및 텍스트 유형에 적합한 분석기로 미리 패키징한다.
            
            ### Character filters
            
            - 문자 필터는 원본 텍스트를 문자 스트림으로 수신하고 문자를 추가, 제거 또는 변경하여 스트림을 변경할 수 있다.
            - 분석기에는 순서대로 적용되는 0개 이상의 문자 필터가 있을 수 있다.
            
            ### Tokenizer
            
            - 토크나이저는 문자 스트림을 수신하여 이를 개별 토큰(일반적으로 개별 단어)로 나누고 토큰 스트림을 출력한다.
            - 토크나이저는 또한 각 용어의 순서나 위치, 해당 용어가 나타내는 원래 단어의 시작 및 끝 문자 offset을 기록하는 일을 담당한다.
            - 분석기에는 정확히 하나의 토크나이저가 있어야 한다.
            
            ### Token filters
            
            - 토큰 필터는 토큰 스트림을 수신하고 토큰을 추가, 제거 또는 변경할 수 있다.
            - 토큰 필터는 각 토큰의 위치나 문자 offset을 변경할 수 없다
            - 분석기에는 순서대로 적용되는 0개 이상의 토큰 필터가 있을 수 있다.
    - Index and search analysis
        
        ### Index and search analysis
        
        - 텍스트 분석은 두 번에 걸쳐 발생한다.
            - index time
                - 문서가 indexed되면 모든 text 필드 값이 분석된다.
            - search time(query time)
                - 필드에서 전체 텍스트 검색을 실행하면 text 쿼리 문자열이 분석된다.
        - 매번 사용되는 분석기 또는 분석 규칙 집합을 각각 인덱스 분석기 또는 검색 분석기라고도 한다.
            
            ### How the index and search analyzer work together
            
            - 대부분의 경우 인덱스 및 검색 시 동일한 분석기를 사용해야 한다.
            - 이를 통해 필드의 값과 쿼리 문자열이 동일한 형태의 토큰으로 변경된다.
            - 결과적으로 이는 검색 중에 토큰이 예상대로 일치하는지 확인한다.
            
            ### When to use a different search analyzer
            
            - 흔하지는 않지만 인덱스 및 검색 시 서로 다른 분석기를 사용하는 것이 합리적일 때도 있다.
            - 이를 활성화하기 위해 Elasticsearch에서는 별도의 검색 분석기를 지정할 수 있다.
            - 일반적으로 별도의 검색 분석기는 필드 값에 대해 동일한 형식의 토큰을 사용할 경우에만 지정해야 하며 쿼리 문자열은 예기치 않거나 관련 없는 검색 일치를 생성한다.
    - Stemming
        
        ### Stemming
        
        - 형태소 분석(Stemming)은 단어를 어근 형태로 줄이는 과정이다. 이를 통해 검색 중 단어 일치의 변형이 보장된다.
        - 일단 형태소가 검색되면 두 단어 중 하나가 검색 시 다른 단어와 일치한다.
        - 형태소 분석은 언어에 따라 다르지만 단어에서 접두사와 접미사를 제거하는 작업이 포함되는 경우가 많다
        - 어떤 경우에는 파생어의 어근 형태가 실제 단어가 아닐 수도 있다. 실제 단어가 아닐지라도 이는 검색에는 중요하지 않다. 단어의 모든 변형이 동일한 어근 형태로 축소되면 올바르게 일치한다.
            
            ### Stemmer token filters
            
            - Elasticsearch에서 형태소 분석은 형태소 분석기 토큰 필터에 의해 처리된다. 이러한 토큰 필터는 단어의 어간을 기준으로 분류할 수 있다.
                - 일련의 규칙을 기반으로 단어를 파생하는 알고리즘 형태소 분석기
                - 사전에서 단어를 검색하여 단어를 파생하는 사전 형태소 분석기
            - 형태소 분석은 토큰을 변경하므로 색인 및 검색 분석 중 동일한 형태소 분석기 토큰 필터를 사용하는 편이 권장된다.
            
            ### Algorithmic stemmers
            
            - 알고리즘 형태소 분석기는 각 단어에 일련의 규칙을 적용하여 단어를 어근 형태로 줄인다.
            - 알고리즘 형태소 분석기에는 다음의 장점이 있다.
                - 설정이 거의 필요하지 않으며 일반적으로 기본적인 사항에 잘 작동한다.
                - 메모리를 거의 사용하지 않는다
                - 일반적으로 사전 형태소 분석기보다 빠르다.
            - 그러나 대부분의 알고리즘 형태소 분석기는 단어의 기존 텍스트만 변경한다.
            - 이는 어근 형태가 포함되지 않은 불규칙 단어에서는 제대로 작동하지 않을 수 있음을 의미한다.
            - 토큰 필터는 알고리즘 형태소 분석을 사용한다.
                - stemmer : 여러 언어에 대한 알고리즘 형태소 분석을 제공하며 일부 언어에는 추가 변형
                - kstem : 알고리즘 형태소 분석과 내장 사전을 결합한 영어 형태소 분석기
                - porter_stem : 영어에 권장되는 알고리즘 형태소 분석기
                - snowball : 여러 언어에 대해 Snowball 기반 형태소 분석 규칙 사용
            
            ### Dictionary stemmers
            
            - 사전 형태소 분석기는 제공된 사전에서 단어를 조회하여 어간이 없는 단어 변형을 사전의 어간 단어로 변경한다.
            - 사전 형태소 분석기는 다음의 경우 적합하다
                - 불규칙한 단어의 형태소 분석
                - 철자가 비슷하지만 개념적으로 관련 없는 단어 식별
            - 알고리즘 형태소 분석기는 일반적으로 사전 형태소 분석기보다 성능이 뛰어나다. 이는 사전 형태소 분석기에 다음의 단점이 있기 때문이다
                - 사전 품질
                    - 사전 형태소 분석기는 사전에 비례하여 우수하다. 사전은 언어 추세에 따라 정기적으로 업데이트되어야 한다
                - 크기 및 성능
                    - 사전 형태소 분석기는 해당 사전의 모든 단어, 접두사 및 접미사를 메모리로 로드해야 한다. 이는 상당한 양의 RAM을 사용할 수 있다.
            - hunspell 토큰 필터를 사용하여 사전 형태소 분석을 수행할 수 있다.
            
            ### Control stemming
            
            - 형태소 분석기를 통해 철자는 유사하지만 개념적으로 관련이 없는 공유 루트 단어가 생성될 수 있다.
            - 이를 방지하고 더 나은 제어 형태소 분석을 위해 다음 토큰 필터를 사용할 수 있다
                - stemmer_override : 특정 토큰의 형태소 분석 규칙을 정의할 수 있다
                - keyword_marker : 지정된 토큰을 키워드로 표시. 키워드 토큰은 후속 형태소 분석기 토큰 필터에 의해 형태소 분석되지 않음
                - conditional : keyword_marker 필터와 유사하게 토큰을 키워드로 표시하는 데 사용
    - Token graphs
        
        ### Token graphs
        
        - 토크나이저는 텍스트를 토큰 스트림으로 변환할 때 다음 사항도 기록한다.
            - 스트림에 있는 각 토큰의 position
            - 토큰이 걸쳐 있는 위치의 수, positionLength
        - 이를 사용하여 방향성 비순환 그래프인 토큰 그래프(Token graphs)를 생성할 수 있다.
        - 토큰 그래프에서 각 위치는 노드를 나타낸다.
            
            ![Untitled](Elastic_Guide(8.9)/Untitled%201.png)
            
            ### Synonyms
            
            - 일부 토큰 필터는 동의어와 같은 새 토큰을 기존 토큰 스트림에 추가할 수 있다.
            - 이러한 동의어는 기존 토큰과 동일한 위치에 걸쳐 있는 경우가 많다.
            
            ### Multi-position tokens
            
            - 일부 토큰 필터는 여러 위치에 걸쳐 토큰을 추가할 수 있다.
            - 여기에는 여러 단어로 구성된 동의어에 대한 토큰이 포함될 수 있다
            - 그러나 그래프 토큰 필터라고 하는 일부 토큰 필터만 다중 위치 토큰에 대한 positionLength를 정확하게 기록한다.
            - 이러한 필터에는 synonym_graph, word_delimiter_graph이 포함된다.
            - nori_tokenizer와 같은 일부 토크나이저는 복합 토큰을 다중 위치 토큰으로 정확하게 분해한다.
                
                ### Using token graphs for search
                
                - 인덱싱은 positionLength 속성을 무시하고 다중 위치 토큰을 포함하는 토큰 그래프를 지원하지 않는다.
                - 그러나 match나 match_phrase와 같은 쿼리는 토큰 그래프를 사용하여 단일 쿼리 문자열에서 여러 하위 쿼리를 생성할 수 있다.
                
                ### Invalid token graphs
                
                - synonym, word_delimiter와 같은 토큰 필터는 여러 위치에 걸쳐 있는 토큰을 추가할 수 있지만 기본 positionLength인 1만 기록한다.
                - 이는 이러한 필터가 해당 토큰을 포함하는 스트림에 대해 잘못된 토큰 그래프를 생성한다는 것을 의미한다.
- Configure text analysis
    - Configure text analysis
        
        ### Configure text analysis
        
        - Elasticsearch는 기본적으로 모든 텍스트 분석에 standard 분석기를 사용한다.
        - standard 분석기는 대부분의 자연어 및 사용 사례에 대한 기본 지원을 제공한다.
        - 표준 분석기가 요구 사항에 맞지 않을 경우 Elasticsearch에 내장된 다른 분석기를 검토하고 테스트할 수 있다. 기본 제공 분석기는 구성이 필요하지 않지만 동작을 조정하는 데 사용할 수 있는 일부 지원 옵션이 있다. 예를 들면 제거할 사용자 지정 중지 단어 목록을 사용하여 standard 분석기를 구성할 수 있다.
    - Test an analyzer
        
        ### Test an analyzer
        
        - analyze API는 분석기에서 생성된 용어를 보기 위한 도구다. 요청에 내장 분석기를 인라인으로 지정할 수 있다.
            
            ```json
            POST _analyze
            {
              "analyzer": "whitespace",
              "text":     "The quick brown fox."
            }
            ```
            
        - analyze 분석기는 단어를 용어 변환하며 각 용어의 순서나 상대 위치, 각 용어의 시작 및 끝 문자 offset도 기록한다.
    - configuring built-in analyzers
        
        ### configuring built-in analyzers
        
        - 내장된 분석기는 별도의 구성 없이 바로 사용할 수 있다. 그러나 일부는 동작을 변경하는 구성 옵션을 지원한다.
            
            ```json
            PUT my-index-000001
            {
              "settings": {
                "analysis": {
                  "analyzer": {
                    "std_english": { 
                      "type":      "standard",
                      "stopwords": "_english_"
                    }
                  }
                }
              },
              "mappings": {
                "properties": {
                  "my_text": {
                    "type":     "text",
                    "analyzer": "standard", 
                    "fields": {
                      "english": {
                        "type":     "text",
                        "analyzer": "std_english" 
                      }
                    }
                  }
                }
              }
            }
            
            POST my-index-000001/_analyze
            {
              "field": "my_text", 
              "text": "The old brown cow"
            }
            
            POST my-index-000001/_analyze
            {
              "field": "my_text.english", 
              "text": "The old brown cow"
            }
            ```
            
    - Create a custom analyzer
        
        ### Create a custom analyzer
        
        - 기본 제공 분석기가 요구 사항을 충족하지 못하는 경우 다음 custom의 적절한 조합을 사용하는 분석기를 만들 수 있다.
            - 0개 이상의 문자 필터
            - Tokenizer
            - 0개 이상의 토큰 필터
            
            ### Configuration
            
            - type
                - 분석기 유형
                - 내장 분석기 유형을 허용한다.
                - 사용자 지정 분석기의 경우 이 매개변수를 생략하거나 custom을 사용한다.
            - tokenizer
                - 필수
                - 내장형 또는 맞춤형 tokenizer
            - char-filter
                - 내장되거나 사용자 정의된 문자 필터의 선택적 배열
            - filter
                - 내장되거나 사용자 정의된 토큰 필터의 선택적 배열
            - position_increment_gap
                - 텍스트 값 배열을 indexing할 때 Elasticsearch는 한 값의 마지막 용어와 다음 값의 첫 번째 용어 사이에 가짜 간격을 삽입하여 구문 쿼리가 다른 배열 요소의 두 용어와 일치하지 않도록 한다.
                - 기본값은 100
            
            ### Example configuration
            
            ```json
            PUT my-index-000001
            {
              "settings": {
                "analysis": {
                  "analyzer": {
                    "my_custom_analyzer": { 
                      "char_filter": [
                        "emoticons"
                      ],
                      "tokenizer": "punctuation",
                      "filter": [
                        "lowercase",
                        "english_stop"
                      ]
                    }
                  },
                  "tokenizer": {
                    "punctuation": { 
                      "type": "pattern",
                      "pattern": "[ .,!?]"
                    }
                  },
                  "char_filter": {
                    "emoticons": { 
                      "type": "mapping",
                      "mappings": [
                        ":) => _happy_",
                        ":( => _sad_"
                      ]
                    }
                  },
                  "filter": {
                    "english_stop": { 
                      "type": "stop",
                      "stopwords": "_english_"
                    }
                  }
                }
              }
            }
            
            POST my-index-000001/_analyze
            {
              "analyzer": "my_custom_analyzer",
              "text": "I'm a :) person, and you?"
            }
            ```
            
    - Specify an analyzer
        
        ### Specify an analyzer
        
        - Elasticsearch는 텍스트 필드, 인덱스, 쿼리, index time, 검색 시간처럼 내장 분석기나 사용자 정의 분석기를 지정하는 다양한 방법을 제공한다.
        - 다양한 수준과 다양한 시간에 분석기를 지정할 수 있지만, 대부분의 경우 간단한 접근 방식이 가장 잘 작동한다.
            
            ### How Elasticsearch determines the index analyzer
            
            - Elasticsearch는 필드에 대한 분석기 mapping 매개변수, analyze.analyzer.default 인덱스 설정을 순서대로 확인하여 사용할 인덱스 분석기를 결정한다.
            - 위 매개변수 중 어느 것도 지정되지 않으면 standard 분석기가 사용된다.
            
            ### Specify the analyzer for a field
            
            - 인덱스를 mapping할 때 분석기 mapping 매개 변수를 사용하여 각 텍스트 필드에 대한 분석기를 지정할 수 있다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "properties": {
                      "title": {
                        "type": "text",
                        "analyzer": "whitespace"
                      }
                    }
                  }
                }
                ```
                
            
            ### Specify the default analyzer for an index
            
            - 필드 수준 분석기 외에도 analyze.analyzer.default 설정을 사용하기 위한 대체 분석기를 설정할 수 있다.
                
                ```json
                PUT my-index-000001
                {
                  "settings": {
                    "analysis": {
                      "analyzer": {
                        "default": {
                          "type": "simple"
                        }
                      }
                    }
                  }
                }
                ```
                
            
            ### How Elasticsearch determines the search analyzer
            
            - 검색 시 Elasticsearch는 다음 매개변수를 순서대로 확인하여 사용할 분석기를 결정한다.
                - analyzer 검색어의 매개변수
                - search_analyzer 필드의 mapping 매개변수
                - 인덱스 analysis.analyzer.default_search 설정
                - analyzer 필드의 mapping 매개변수
            - 위 매개변수 중 어느 것도 지정되지 않으면 standard 분석기 사용
            
            ### Specify the search analyzer for a query
            
            - 전체 텍스트 쿼리를 작성할 때 analyzer 매개 변수를 사용하여 검색 분석기를 지정할 수 있다.
            - 제공된 경우 다른 검색 분석기를 재정의한다.
                
                ```json
                GET my-index-000001/_search
                {
                  "query": {
                    "match": {
                      "message": {
                        "query": "Quick foxes",
                        "analyzer": "stop"
                      }
                    }
                  }
                }
                ```
                
            
            ### Specify the search analyzer for a field
            
            - 인덱스를 mapping할 때 search_analyzer mapping 매개변수를 사용하여 각 텍스트 필드에 대한 검색 분석기를 지정할 수 있다.
            - 검색 분석기가 제공되는 경우 분석기 매개 변수를 사용하여 인덱스 분석기도 지정해야 한다.
                
                ```json
                PUT my-index-000001
                {
                  "mappings": {
                    "properties": {
                      "title": {
                        "type": "text",
                        "analyzer": "whitespace",
                        "search_analyzer": "simple"
                      }
                    }
                  }
                }
                ```
                
            
            ### Specify the default search analyzer for an index
            
            - 인덱스를 생성할 때 analyze.analyzer.default_search 설정을 사용하여 기본 검색 분석기를 설정할 수 있다.
            - 검색 분석기가 제공되는 경우 analyze.analyzer.default 설정을 사용하여 기본 인덱스 분석기도 지정해야 한다.
                
                ```json
                PUT my-index-000001
                {
                  "settings": {
                    "analysis": {
                      "analyzer": {
                        "default": {
                          "type": "simple"
                        },
                        "default_search": {
                          "type": "whitespace"
                        }
                      }
                    }
                  }
                }
                ```
                
- Bulit-in analayzer reference
    - Bulit-in analayzer reference
        
        ### Bulit-in analayzer reference
        
        - Standard Analyzer
            - 유니코드 텍스트 분할 알고리즘에 정의된 대로 텍스트를 단어 경계에 따라 용어로 나눈다. 대부분의 구두점을 제거하고, 용어를 소문자로 바꾸고, 중지 단어 제거를 지원한다.
        - Simple Analyzer
            - 글자가 아닌 문자를 만날 때마다 텍스트를 용어로 나눈다. 모든 용어를 소문자로 만든다.
        - Whitespace Analyzer
            - 공백 문자가 나타날 때마다 텍스트를 용어로 나눈다. 용어를 소문자로 사용하지 않는다.
        - Stop Analyzer
            - Simple Analyzer와 유사하지만 중지 단어 제거도 지원한다.
        - Keyword Analyzer
            - 주어진 텍스트가 무엇이든 받아들이고 단일 용어와 정확히 동일한 텍스트를 출력하는 noop 분석기
        - Pattern Analyzer
            - 정규식을 사용하여 텍스트를 용어로 분할
            - 소문자 및 중지 단어 지원
        - Language Analyzer
            - English, French와 같은 다양한 언어별 분석기 제공
        - Fingerprint Analyzer
            - 중복 감지에 사용할 수 있는 지문을 생성하는 전문 분석기
        - Custom Analyzer
            - 요구 사항에 적합한 분석기가 없을 경우 문자 필터, 토크나이저, 토큰 필터를 결합한 custom 분석기를 만들 수 있다.
    - Fingerprint
        
        ### Fingerprint analyzer
        
        - fingerprint 분석기는 클러스터링을 지원하기 위해 OpenRefine 프로젝트에서 사용하는 핑거프린팅 알고리즘을 구현한다
        - 입력 텍스트는 소문자로 변환되고, 확장 문자를 제거하기 위해 정규화되고, 정렬되고, 중복 제거되고, 단일 토큰으로 연결된다.
        - 불용어 목록이 구성되면 불용어도 제거된다.
            
            ### Example output
            
            ```json
            POST _analyze
            {
              "analyzer": "fingerprint",
              "text": "Yes yes, Gödel said this sentence is consistent and."
            }
            ```
            
            ```json
            [ and consistent godel is said sentence this yes ]
            ```
            
            ### Configuration
            
            - separator
                - 용어를 연결하는 데 사용할 문자. 기본 값은 공백
            - max_output_size
                - 방출할 최대 토큰 크기
                - 기본 값은 255
                - 매개변수 값보다 큰 토큰은 폐기된다.
            - stopwords
                - 사전 정의된 중지 단어 목록
                - _english 또는 중지 단어 목록을 포함하는 배열
                - 기본 값은 _none_
            - stopwords_path
                - 불용어가 포함된 파일의 경로
            
            ### Example configuration
            
            ```json
            PUT my-index-000001
            {
              "settings": {
                "analysis": {
                  "analyzer": {
                    "my_fingerprint_analyzer": {
                      "type": "fingerprint",
                      "stopwords": "_english_"
                    }
                  }
                }
              }
            }
            
            POST my-index-000001/_analyze
            {
              "analyzer": "my_fingerprint_analyzer",
              "text": "Yes yes, Gödel said this sentence is consistent and."
            }
            ```
            
            ```json
            [ consistent godel said sentence yes ]
            ```
            
            ### Definition
            
            - fingerprint 토크나이저는 다음으로 구성된다.
                - Tokenizer
                    - Standard Tokenizer
                - Token Filters (in order)
                    - Lower Case Token Filter
                    - ASCII folding
                    - Stop Token Filter (disabled by default)
                    - Fingerprint
            - 구성 매개변수 이상으로 fingerprint 분석기를 사용자 정의해야 하는 경우 이를 사용자 정의 분석기로 다시 만들고 일반적으로 토큰 필터를 추가하여 수정해야 한다.
            - 이를 통해 내장 지문 분석기가 다시 생성되며 이를 추가 사용자 정의의 시작점으로 사용할 수 있다.
                
                ```json
                PUT /fingerprint_example
                {
                  "settings": {
                    "analysis": {
                      "analyzer": {
                        "rebuilt_fingerprint": {
                          "tokenizer": "standard",
                          "filter": [
                            "lowercase",
                            "asciifolding",
                            "fingerprint"
                          ]
                        }
                      }
                    }
                  }
                }
                ```
                
    - Keyword
        
        ### Keyword analyzer
        
        - Keyword 분석기는 전체 입력 문자열을 단일 토큰으로 반환하는 “noop” 분석기다.
            
            ### Example output
            
            ```json
            POST _analyze
            {
              "analyzer": "keyword",
              "text": "The 2 QUICK Brown-Foxes jumped over the lazy dog's bone."
            }
            ```
            
            ```json
            [ The 2 QUICK Brown-Foxes jumped over the lazy dog's bone. ]
            ```
            
            ### Configuration
            
            - keyword 분석기는 매개변수를 설정할 수 없다.
            
            ### Definition
            
            - keyword 분석기는 다음으로 구현된다.
                - Tokenizer
                    - keyword Tokenizer
            - 키워드 분석기를 사용자 정의해야 하는 경우 이를 사용자 정의 분석기로 다시 생성하고 일반적으로 토큰 필터를 추가하여 수정해야 한다.
            - 일반적으로 토큰으로 분할되지 않은 문자열을 원할 경우 키워드 유형을 선호해야 하지만, 필요한 경우 내장 키워드 분석기를 다시 생성하여 추가 사용자 정의를 위한 시작점으로 사용할 수 있다.
                
                ```json
                PUT /keyword_example
                {
                  "settings": {
                    "analysis": {
                      "analyzer": {
                        "rebuilt_keyword": {
                          "tokenizer": "keyword",
                          "filter": [         
                          ]
                        }
                      }
                    }
                  }
                }
                ```
                
    - Language
        
        ### Language analyzers
        
        - 특정 언어 텍스트 분석을 목표로 하는 분석기 종류다.
        - 모든 분석기는 구성에서 내부적으로 또는 stopwords_path를 설정하여 외부 불용어 파일을 사용하여 사용자 정의 불용어 설정을 지원한다.
        - stem_exclusion 매개변수를 사용하면 형태소 분석을 하지 않아야 하는 소문자 단어 배열을 지정할 수 있다. 내부적으로 이 기능은 keywords_stem_exclusion 매개변수 값에 설정된 keyword_marker 필터를 추가하여 구현된다.
    - Pattern
        
        ### Pattern analyzer
        
        - pattern 분석기는 정규식을 사용하여 텍스트를 용어로 분할한다.
        - 정규식은 토큰 자체가 아닌 토큰 구분 기호와 일치해야 한다.
        - 정규식의 기본 값은 \W+
            
            ### Example output
            
            ```json
            POST _analyze
            {
              "analyzer": "pattern",
              "text": "The 2 QUICK Brown-Foxes jumped over the lazy dog's bone."
            }
            ```
            
            ```json
            [ the, 2, quick, brown, foxes, jumped, over, the, lazy, dog, s, bone ]
            ```
            
            ### Configuration
            
            - pattern
                - Java 정규 표현식. 기본 값은 \W+
            - flags
                - Java 정규식 플래그. 플래그는 파이프로 구분되어야 한다.
            - lowercase
                - 용어를 소문자로 써야 할지 여부. 기본 값은 true
            - stopwords
                - 사전 정의된 중지 단어 목록 _english 또는 중지 단어 목록을 포함하는 배열. 기본 값은 _none_
            - stopwords_path
                - 불용어가 포함된 파일의 경로
            
            ### Example configuration
            
            ```json
            PUT my-index-000001
            {
              "settings": {
                "analysis": {
                  "analyzer": {
                    "my_email_analyzer": {
                      "type":      "pattern",
                      "pattern":   "\\W|_", 
                      "lowercase": true
                    }
                  }
                }
              }
            }
            
            POST my-index-000001/_analyze
            {
              "analyzer": "my_email_analyzer",
              "text": "John_Smith@foo-bar.com"
            }
            ```
            
            ```json
            [ john, smith, foo, bar, com ]
            ```
            
            ### Definition
            
            - Pattern 분석기의 구성은 다음과 같다.
                - Tokenizer
                    - Pattern Tokenizer
                - Token Filters
                    - Lower Case Token Filter
                    - Stop Token Filter (기본 값 disabled)
            - 구성 매개변수 이상으로 패턴 분석기를 사용자 정의해야 하는 경우 이를 사용자 정의 분석기로 다시 생성하고 일반적으로 토큰 필터를 추가하여 수정해야 한다.
                
                ```json
                PUT /pattern_example
                {
                  "settings": {
                    "analysis": {
                      "tokenizer": {
                        "split_on_non_word": {
                          "type":       "pattern",
                          "pattern":    "\\W+" 
                        }
                      },
                      "analyzer": {
                        "rebuilt_pattern": {
                          "tokenizer": "split_on_non_word",
                          "filter": [
                            "lowercase"       
                          ]
                        }
                      }
                    }
                  }
                }
                ```
                
    - Simple
        
        ### Simple analyzer
        
        - simple 분석기는 숫자, 공백, 하이픈, 아포스트로피 등 문자가 아닌 문자에서 텍스트를 토큰으로 나누고 문자가 아닌 문자를 삭제하며 대문자를 소문자로 변경한다.
            
            ### Example
            
            ```json
            POST _analyze
            {
              "analyzer": "simple",
              "text": "The 2 QUICK Brown-Foxes jumped over the lazy dog's bone."
            }
            ```
            
            ```json
            [ the, quick, brown, foxes, jumped, over, the, lazy, dog, s, bone ]
            ```
            
            ### Definition
            
            - Simple 분석기는 하나의 토크나이저로 정의된다.
                - Tokenizer
                    - Lowercase Tokenizer
    - Standard
        
        ### Standard analyzer
        
        - standard 분석기는 아무것도 지정되지 않은 경우 사용되는 기본 분석기다.
        - 이는 문법 기반 토큰화를 제공하며 대부분의 언어에서 잘 작동한다.
            
            ### Example output
            
            ```json
            POST _analyze
            {
              "analyzer": "standard",
              "text": "The 2 QUICK Brown-Foxes jumped over the lazy dog's bone."
            }
            ```
            
            ```json
            [ the, 2, quick, brown, foxes, jumped, over, the, lazy, dog's, bone ]
            ```
            
            ### Configuration
            
            - Standard 분석기는 다음의 매개변수를 가진다.
            - max_token_length
                - 최대 토큰 길이
                - 이 길이를 초과하는 토큰이 발견되면 max_token_length 간격으로 분할된다.
                - 기본 값은 255
            - stopwords
                - 사전 정의된 중지 단어 목록 _english_ 또는 중지 단어 목록을 포함하는 배열
                - 기본 값은 _none_
            - stopwords_path
                - 불용어가 포함된 파일의 경로
            
            ### Definition
            
            - Standard 분석기는 다음으로 구성된다.
                - Tokenizer
                    - Standard Tokenizer
                - Token Filters
                    - Lower Case Token Filter
                    - Stop Token Filter (기본 값 disabled)
            - 구성 매개변수 이상으로 Standard 분석기를 사용자 정의해야 하는 경우 이를 사용자 정의 분석기로 다시 만들고 일반적으로 토큰 필터를 추가하여 수정해야 한다.
    - Stop
        
        ### Stop
        
        - Stop 분석기는 simple 분석기와 동일 하지만 불용어 제거에 대한 지원을 추가한다. 기본적으로 _english_ 중지 단어를 사용한다.
            
            ### Example output
            
            ```json
            POST _analyze
            {
              "analyzer": "stop",
              "text": "The 2 QUICK Brown-Foxes jumped over the lazy dog's bone."
            }
            ```
            
            ```json
            [ quick, brown, foxes, jumped, over, lazy, dog, s, bone ]
            ```
            
            ### Configuration
            
            - 다음 매개변수를 허용한다.
            - stopwords
                - 사전 정의된 중지 단어 목록 _english_ 또는 중지 단어 목록을 포함하는 배열
                - 기본 값은 _english_
            - stopwords_path
                - 불용어가 포함된 파일의 경로
                - Elasticsearch config 디렉터리에 상대적(relative)이다.
            
            ### Definition
            
            - 다음으로 구성된다.
                - Tokenizer
                    - Lower Case Tokenizer
                - Token filters
                    - Stop Token Filter
            - 구성 매개변수 이상으로 Standard 분석기를 사용자 정의해야 하는 경우 이를 사용자 정의 분석기로 다시 만들고 일반적으로 토큰 필터를 추가하여 수정해야 한다.
    - Whitespace
        
        ### Whitespace analyzer
        
        - Whitespace 분석기는 공백 문자를 발견할 때마다 텍스트를 용어로 나눈다.
            
            ### Example output
            
            ```json
            POST _analyze
            {
              "analyzer": "whitespace",
              "text": "The 2 QUICK Brown-Foxes jumped over the lazy dog's bone."
            }
            ```
            
            ```json
            [ The, 2, QUICK, Brown-Foxes, jumped, over, the, lazy, dog's, bone. ]
            ```
            
            ### Configuration
            
            - whitespace 분석기는 설정할 수 없다.
            
            ### Definition
            
            - 다음으로 구성된다.
            - Tokenizer
                - Whitespace Tokenizer
            - 구성 매개변수 이상으로 Standard 분석기를 사용자 정의해야 하는 경우 이를 사용자 정의 분석기로 다시 만들고 일반적으로 토큰 필터를 추가하여 수정해야 한다.
- Tokenizer reference
    - Tokenizer reference
        
        ### Tokenizer reference
        
        - Tokenizer는 문자 stream을 수신하여 이를 개별 토큰으로 나누고 토큰 stream을 출력한다.
        - Tokenizer는 다음 사항을 기록하는 역할도 담당한다.
            - 각 용어의 순서 또는 위치
            - 용어가 나타내는 원래 단어의 시작 및 끝 문자 offset
            - 토큰 유형 또는 생성된 각 용어의 분류. 간단한 분석기는 word 토큰 타입만 제공한다.
            
            ### Word Oriented Tokenizers
            
            - 일반적으로 전체 텍스트를 개별 단어로 Tokenizing하는 데 사용된다.
                - Standard Tokenizer
                    - Standard Tokenizer는 유니코드 텍스트 분할 알고리즘에 정의된 대로 텍스트를 단어 경계에 따라 용어로 나눈다.
                    - 대부분의 구두점 기호를 제거한다.
                    - 대부분의 언어에 가장 적합한 선택이다.
                - Letter Tokenizer
                    - Letter Tokenizer는 문자가 아닌 문자를 발견할 때마다 텍스트를 용어로 나눈다.
                - Lowercase Tokenizer
                    - lowercase Tokenizer는 letter Tokenizer와 마찬가지로 문자가 아닌 문자를 만날 때마다 텍스트를 용어로 나누지만 모든 용어를 소문자로 표시하기도 한다.
                - Whitespace Tokenizer
                    - Whitespace Tokenizer는 공백 문자를 만날 때마다 텍스트를 용어로 나눈다.
                - UAX URL Email Tokenizer
                    - uax_url_email Tokenizer는 URL과 이메일 주소를 단일 토큰으로 인식한다는 점을 제외하면 standard Tokenizer와 유사하다.
                - Classic Tokenizer
                    - classic Tokenizer는 영어용 문법 기반 Tokenizer다.
                - Thai Tokenizer
                    - Thai Tokenizer는 태국어 텍스트를 단어로 분할한다.
            
            ### Partial Word Tokenizers
            
            - 부분적인 단어 일치를 위해 텍스트나 단어를 작은 조각으로 나눈다.
                - N-Gram Tokenizer
                    - ngram Tokenizer는 지정된 문자 목록을 발견하면 텍스트를 단어로 분할한 다음 각 단어의 n-gram을 반환한다.
                - Edge N-Gram Tokenizer
                    - edge_ngram Tokenizer는 지정된 문자 목록을 발견하면 텍스트를 단어로 분할한 다음 단어 시작 부분에 고정된 각 단어의 n-gram을 반환한다.
            
            ### Structured Text Tokenizers
            
            - 일반적으로 전체 텍스트보다는 식별자, 이메일 주소, 우편번호, 경로와 같은 구조화된 텍스트와 함께 사용된다.
                - Keyword Tokenizer
                    - Keyword Tokenizer는 주어진 모든 텍스트를 받아들이고 단일 용어와 정확히 동일한 텍스트를 출력하는 “noop” 토크나이저다.
                    - 소문자 등의 토큰 필터와 결합하여 분석된 용어를 정규화할 수 있다.
                - Pattern Tokenizer
                    - Patter Tokenizer는 정규식을 사용하여 텍스트가 단어 구분 기호와 일치할 때마다 텍스트를 용어로 분할하거나 일치하는 텍스트를 용어로 캡처한다.
                - Simple Pattern Tokenizer
                    - Simple Pattern Tokenizer는 정규식을 사용하여 일치하는 텍스트를 용어로 캡처한다.
                    - 정규식 기능의 제한된 하위 집합을 사용하여 일반적으로 pattern Tokenizer보다 빠르다.
                - Char Group Tokenizer
                    - Char Group Tokenizer는 분할할 문자 세트를 통해 구성할 수 있으며, 이는 일반적으로 정규식을 실행하는 것보다 비용이 저렴하다.
                - Simple Pattern Split Tokenizer
                    - Simple Pattern Split Tokenizer는 Simple Pattern Tokenizer와 동일한 제한된 정규식 하위 집합을 사용하지만 일치 항목을 용어로 반환하는 대신 일치 항목에서 입력을 분할한다.
                - Path Tokenizer
                    - Path Hierarchy Tokenizer는 파일 시스템 경로와 같은 계층적 값을 취하고, 경로 구분 기호로 분할하고, 트리의 각 구성 요소에 대한 용어를 내보낸다.
    - Character group
        
        ### Character group Tokenizer
        
        - char_group Tokenizer는 정의된 세트에 있는 문자를 만날 때마다 텍스트를 용어로 나눈다.
        - 간단한 사용자 정의 토큰화가 필요하고 pattern Tokenizer 사용에 따른 오버헤드가 허용되지 않는 경우에 주로 유용하다.
            
            ### Configuration
            
            - char_group Tokenizer는 하나의 매개변수만 허용한다.
                - tokenize_on_chars
                    - 문자열을 토큰화할 문자 목록이 포함된 목록.
                    - 이 목록의 문자를 만날 때마다 새 토큰이 시작된다.
                - max_token_length
                    - 최대 토큰 길이.
                    - 이 길이를 초과하는 토큰이 발견되면 max_token_length 간격으로 분할된다.
                    - 기본 값은 255
            
            ### Example output
            
            ```json
            POST _analyze
            {
              "tokenizer": {
                "type": "char_group",
                "tokenize_on_chars": [
                  "whitespace",
                  "-",
                  "\n"
                ]
              },
              "text": "The QUICK brown-fox"
            }
            ```
            
            ```cpp
            {
              "tokens": [
                {
                  "token": "The",
                  "start_offset": 0,
                  "end_offset": 3,
                  "type": "word",
                  "position": 0
                },
                {
                  "token": "QUICK",
                  "start_offset": 4,
                  "end_offset": 9,
                  "type": "word",
                  "position": 1
                },
                {
                  "token": "brown",
                  "start_offset": 10,
                  "end_offset": 15,
                  "type": "word",
                  "position": 2
                },
                {
                  "token": "fox",
                  "start_offset": 16,
                  "end_offset": 19,
                  "type": "word",
                  "position": 3
                }
              ]
            }
            ```
            
    - Classic
        
        ### Classic Tokenizer
        
        - Classic Tokenizer는 영어 문서에 적합한 문법 기반 Tokenizer다.
        - Classic Tokenizer는 약어, 회사 이름, 이메일 주소 및 인터넷 호스트 이름을 특별하게 처리하기 위한 경험적 방법을 갖추고 있다.
        - 그러나 규칙이 항상 작동하는 것은 아니며, 영어 이외의 대부분의 언어에서는 제대로 작동하지 않는다.
            - 대부분의 구두점 문자에서 단어를 분할하고 구두점을 제거한다.
            - 토큰에 숫자가 있는 경우를 제외하고 하이픈으로 단어를 분할한다.
            - 이메일 주소와 인터넷 호스트 이름을 하나의 토큰으로 인식한다.
            
            ### Example output
            
            ```json
            POST _analyze
            {
              "tokenizer": "classic",
              "text": "The 2 QUICK Brown-Foxes jumped over the lazy dog's bone."
            }
            ```
            
            ```json
            [ The, 2, QUICK, Brown, Foxes, jumped, over, the, lazy, dog's, bone ]
            ```
            
            ### Configuration
            
            - classic 토크나이저는 다음 매개변수를 허용한다
                - max_token_length
                    - 토큰의 최대 길이
                    - 이 길이를 초과하는 토큰은 max_token_length 간격을 두고 분할된다.
                    - 기본 값은 255
            
            ### Example Configuration
            
            ```json
            PUT my-index-000001
            {
              "settings": {
                "analysis": {
                  "analyzer": {
                    "my_analyzer": {
                      "tokenizer": "my_tokenizer"
                    }
                  },
                  "tokenizer": {
                    "my_tokenizer": {
                      "type": "classic",
                      "max_token_length": 5
                    }
                  }
                }
              }
            }
            
            POST my-index-000001/_analyze
            {
              "analyzer": "my_analyzer",
              "text": "The 2 QUICK Brown-Foxes jumped over the lazy dog's bone."
            }
            ```
            
            ```json
            [ The, 2, QUICK, Brown, Foxes, jumpe, d, over, the, lazy, dog's, bone ]
            ```
            
    - Edge n-gram
        
        ### Edge n-gram tokenizer
        
        - edge_ngram Tokenizer는 지정된 문자 목록 중 하나를 만날 때마다 먼저 텍스트를 단어로 나눈 다음 N-gram의 시작이 단어의 시작 부분에 고정되는 각 단어의 N-gram을 내보낸다.
        - Edge N-Gram은 입력 시 검색하는 쿼리에 유용하다.
            
            ### Example output
            
            - 기본 설정을 사용하면 edge_ngram 토크나이저는 초기 텍스트를 단일 토큰으로 처리하고 최소 길이 1과 최대 길이 2의 N-gram을 생성한다.
            
            ```json
            POST _analyze
            {
              "tokenizer": "edge_ngram",
              "text": "Quick Fox"
            }
            ```
            
            ```json
            [ Q, Qu ]
            ```
            
            ### Configuration
            
            - edge_ngram 토크나이저가 사용하는 매개변수 목록
                - min_gram
                    - 그램의 최소 문자 길이
                    - 기본 값은 1
                - max_gram
                    - 그램의 최대 문자 길이
                    - 기본 값은 2
                - token_chars
                    - 토큰에 포함되어야 하는 문자 클래스
                    - 지정된 클래스에 속하지 않는 문자를 기준으로 분할한다.
                    - 기본 값은 [](모든 문자 유지)
                    - 문자 클래스는 다음 등을 사용한다.
                        - letter, digit, whitespace, punctuation, symbol, custom
                - custom_token_chars
                    - 토큰의 일부로 처리되어야 하는 사용자 정의 문자.
            
            ### Limitations of the max_gram parameter
            
            - edge_ngram 토크나이저의 max_gram 값은 토큰의 문자 길이를 제한한다.
            - edge_ngram 토크나이저를 인덱스 분석기와 함께 사용하는 경우 max_gram 길이보다 긴 검색어가 인덱스된 용어와 일치하지 않을 수 있다.
            
            ### Example configuration
            
            ```json
            PUT my-index-000001
            {
              "settings": {
                "analysis": {
                  "analyzer": {
                    "my_analyzer": {
                      "tokenizer": "my_tokenizer"
                    }
                  },
                  "tokenizer": {
                    "my_tokenizer": {
                      "type": "edge_ngram",
                      "min_gram": 2,
                      "max_gram": 10,
                      "token_chars": [
                        "letter",
                        "digit"
                      ]
                    }
                  }
                }
              }
            }
            
            POST my-index-000001/_analyze
            {
              "analyzer": "my_analyzer",
              "text": "2 Quick Foxes."
            }
            ```
            
            ```json
            [ Qu, Qui, Quic, Quick, Fo, Fox, Foxe, Foxes ]
            ```
            
            - 일반적으로 인덱스 시간과 검색 시간에 분석기를 동일한 것을 사용하는 것이 좋다.
            - edge_ngram 토크나이저의 경우 인덱스에서 일부 단어를 일치시킬 수 있도록 인덱스 시간에 edge_ngram 토크나이저를 사용하는 것이 합리적이다.
            - 인덱스 분석기의 max_gram 값을 초과하는 검색어는 색인된 용어와 일치하지 않을 수 있다.
    - Keyword
        
        ### Keyword Tokenizer
        
        - keyword 토크나이저는 주어진 모든 텍스트를 받아들이고 단일 용어와 정확히 동일한 텍스트를 출력하는 “noop” 토크나이저다.
        - 출력을 정규화하기 위해 토큰 필터와 결합할 수 있다.
            
            ### Example output
            
            ```json
            POST _analyze
            {
              "tokenizer": "keyword",
              "text": "New York"
            }
            ```
            
            ```json
            [ New York ]
            ```
            
            ### Combine with token filters
            
            - keyword 토크나이저를 토큰 필터와 결합하여 제품 ID나 이메일 주소와 같은 구조화된 데이터를 정규화할 수 있다.
                
                ```json
                POST _analyze
                {
                  "tokenizer": "keyword",
                  "filter": [ "lowercase" ],
                  "text": "john.SMITH@example.COM"
                }
                ```
                
                ```json
                [ john.smith@example.com ]
                ```
                
            
            ### Configuration
            
            - keyword 토크나이저가 허용하는 매개변수는 다음과 같다.
                - buffer_size
                    - 단일 패스에서 용어 버퍼로 읽어온 문자 수
                    - 기본 값은 256
                    - 용어 버퍼는 모든 텍스트가 소비될 때까지 buffer_size만큼 커진다.
                    - 해당 설정은 변경하지 않는 편이 권장된다.
    - Letter
        
        ### Letter Tokenizer
        
        - letter 토크나이저는 문자가 아닌 문자를 발견할 때마다 텍스트를 용어로 나눈다.
        - 대부분의 유럽 언어에서는 합리적인 작업을 수행하지만 단어가 공백으로 구분되지 않는 아시아 언어에서는 제대로 작동하지 않는다.
            
            ### Example output
            
            ```json
            POST _analyze
            {
              "tokenizer": "letter",
              "text": "The 2 QUICK Brown-Foxes jumped over the lazy dog's bone."
            }
            ```
            
            ```json
            [ The, QUICK, Brown, Foxes, jumped, over, the, lazy, dog, s, bone ]
            ```
            
            ### Configuration
            
            - letter 토크나이저에는 매개변수가 없다.
    - Lowercase
        
        ### Lowercase tokenizer
        
        - lowercase 토크나이저는 letter 토크나이저와 마찬가지로 문자가 아닌 문자를 만날 때마다 텍스트를 용어로 나누지만 모든 용어를 소문자로 표시하기도 한다.
        - 이는 lowercase 토큰 필터와 결합된 letter 토크나이저와 기능적으로 동일 하지만, 단일 패스에서 두 단계를 모두 수행하므로 더 효율적이다.
            
            ### Example output
            
            ```json
            POST _analyze
            {
              "tokenizer": "lowercase",
              "text": "The 2 QUICK Brown-Foxes jumped over the lazy dog's bone."
            }
            ```
            
            ```json
            [ the, quick, brown, foxes, jumped, over, the, lazy, dog, s, bone ]
            ```
            
            ### Configuration
            
            - lowercase 토크나이저는 매개변수가 허용되지 않는다.
    - N-gram
        
        ### N-gram tokenizer
        
        - ngram 토크나이저는 지정된 문자 목록 중 하나를 만날 때마다 먼저 텍스트를 단어로 나눈 다음 지정된 길이의 각 단어에 대한 N-Gram을 내보낸다.
        - N-gram은 단어(지정된 길이의 연속적인 문자 시퀸스)를 가로질러 이동하는 슬라이딩 창과 같다.
        - 공백을 사용하지 않거나 긴 복합어가 있는 언어를 쿼리하는 데 유용하다.
            
            ### Example output
            
            ```json
            POST _analyze
            {
              "tokenizer": "ngram",
              "text": "Quick Fox"
            }
            ```
            
            ```json
            [ Q, Qu, u, ui, i, ic, c, ck, k, "k ", " ", " F", F, Fo, o, ox, x ]
            ```
            
            ### Configuration
            
            - ngram 토크나이저가 허용하는 매개변수는 다음과 같다.
                - min_gram
                    - 그램의 최소 문자 길이
                    - 기본 값은 1
                - max_gram
                    - 그램의 최대 문자 길이
                    - 기본 값은 2
                - token_chars
                    - 토큰에 포함되어야 하는 문자 클래스
                    - 지정된 클래스에 속하지 않는 문자를 기준으로 분할한다.
                    - 기본 값은 [](모든 문자 유지)
                    - 문자 클래스는 다음 등을 사용한다.
                        - letter, digit, whitespace, punctuation, symbol, custom
                - custom_token_chars
                    - 토큰의 일부로 처리되어야 하는 사용자 정의 문자.
            - 일반적으로 min_gram과 max_gram은 동일한 값을 설정하는 것이 권장된다.
            - 길이가 작을수록 더 많은 문서가 일치하지만 일치 품질은 낮아진다.
            - 길이가 길수록 일치 항목이 더 구체적이다.
    - Path hierarchy
        
        ### Path hierarchy tokenizer
        
        - path_hierarchy 토크나이저는 파일 시스템 경로와 같은 계층적 값을 취하고, 경로 구분 기호로 분할하고, 트리의 각 구성 요소에 대한 용어를 내보낸다.
        - path_hierarcy 토크나이저는 Lucene의 PathHierarchyTokenizer를 사용한다.
            
            ### Example output
            
            ```json
            POST _analyze
            {
              "tokenizer": "path_hierarchy",
              "text": "/one/two/three"
            }
            ```
            
            ```json
            [ /one, /one/two, /one/two/three ]
            ```
            
            ### Configuration
            
            - path_hierarchy 토크나이저는 다음 매개변수를 허용한다.
                - delimiter
                    - 경로 구분 기호로 사용할 문자
                    - 기본 값은 /
                - replacement
                    - 구분 기호에 사용할 선택적 대체 문자
                    - 기본 값은 delmiter
                - buffer_size
                    - 단일 패스에서 용어 버퍼로 읽어온 문자 수
                    - 기본 값은 1024
                    - 버퍼라는 용어는 모든 텍스트가 소비될 때까지 이 크기만큼 커진다
                    - 이 설정은 변경하지 않는 것이 권장된다.
                - reverse
                    - true 일 경우 도메인과 같은 계층 구조에 적합한 Lucene의 ReversePathHierarchyTokenizer를 사용한다.
                    - 기본 값은 false
                - skip
                    - 건너뛸 초기 토큰 수
                    - 기본 값은 0
    - Pattern
        
        ### Pattern tokenizer
        
        - pattern 토크나이저는 정규식을 사용하여 텍스트가 단어 구분 기호와 일치할 때마다 텍스트를 용어로 분할하거나 일치하는 텍스트를 용어로 캡처한다.
        - 기본 패턴은 단어가 아닌 문자를 만날 때마다 텍스트를 분할하는 \W+
            
            ### Example output
            
            ```json
            POST _analyze
            {
              "tokenizer": "pattern",
              "text": "The foo_bar_size's default is 5."
            }
            ```
            
            ```json
            [ The, foo_bar_size, s, default, is, 5 ]
            ```
            
            ### Configuration
            
            - 다음 매개변수를 허용한다.
                - pattern
                    - Java 정규 표현식. 기본 값은 \W+
                - flags
                    - Java 정규식 플래그
                    - 플래그는 파이프로 구분되어야 한다.
                - group
                    - 토큰으로 추출할 캡처 그룹
                    - 기본 값은 -1(분할)
    - Simple pattern
        
        ### Simple pattern tokenizer
        
        - simple_pattern 토크나이저는 정규식을 사용하여 일치하는 텍스트를 용어로 캡처한다. 지원하는 정규식 기능 세트는 pattern 토크나이저보다 제한적이지만 일반적으로 토큰화가 더 빠르다.
        - simple_pattern 토크나이저는 pattern 토크나이저와 달리 패턴 일치에 대한 입력 분할을 지원하지 않는다.
        - 동일한 정규식 하위 집합을 사용하여 패턴 일치를 분할하려면 simple_pattern_split 토크나이저를 사용해야 한다.
        - simple_pattern 토크나이저는 Lucene 정규식을 사용한다.
        - 기본 패턴은 용어를 생성하지 않는 빈 문자열이다. simple_pattern 토크나이저는 항상 기본이 아닌 패턴으로 구성되어야 한다.
            
            ### Configuration
            
            - 허용되는 매개변수는 다음과 같다.
                - pattern
                    - Lucene 정규식, 기본값이 빈 문자열
            
            ### Example configuration
            
            ```json
            PUT my-index-000001
            {
              "settings": {
                "analysis": {
                  "analyzer": {
                    "my_analyzer": {
                      "tokenizer": "my_tokenizer"
                    }
                  },
                  "tokenizer": {
                    "my_tokenizer": {
                      "type": "simple_pattern",
                      "pattern": "[0123456789]{3}"
                    }
                  }
                }
              }
            }
            
            POST my-index-000001/_analyze
            {
              "analyzer": "my_analyzer",
              "text": "fd-786-335-514-x"
            }
            ```
            
            ```json
            [ 786, 335, 514 ]
            ```
            
    - Simple pattern split
        
        ### Simple pattern split tokenizer
        
        - simple_pattern_split 토크나이저는 정규식을 사용하여 패턴 일치 시 입력을 용어로 분할한다.
        - 지원하는 정규식 기능 세트는 pattern 토크나이저보다 더 제한적이지만 일반적으로 토큰화가 더 빠르다
        - simple_pattern split 토크나이저는 일치 항목 자체에서 용어를 생성하지 않는다.
        - 동일한 제한된 정규식 하위 집합의 패턴을 사용하여 일치 항목에서 용어를 생성하려면 simple_pattern 토크나이저를 참조해야 한다.
        - Lucene 정규식을 사용한다
        - 기본 패턴은 전체 입력을 포함하는 하나의 용어를 생성하는 빈 문자열이다. 항상 기본이 아닌 패턴으로 구성되어야 한다.
            
            ### Configuration
            
            - 허용되는 매개변수는 다음과 같다.
                - pattern
                    - Lucene 정규 표현식.
                    - 기본 값은 빈 문자열
            
            ### Example configuration
            
            ```json
            PUT my-index-000001
            {
              "settings": {
                "analysis": {
                  "analyzer": {
                    "my_analyzer": {
                      "tokenizer": "my_tokenizer"
                    }
                  },
                  "tokenizer": {
                    "my_tokenizer": {
                      "type": "simple_pattern_split",
                      "pattern": "_"
                    }
                  }
                }
              }
            }
            
            POST my-index-000001/_analyze
            {
              "analyzer": "my_analyzer",
              "text": "an_underscored_phrase"
            }
            ```
            
            ```json
            [ an, underscored, phrase ]
            ```
            
    - Standard
        
        ### Standard tokenizer
        
        - standard 토크나이저는 문자 기반 토큰화를 제공하며 대부분의 언어에서 잘 작동한다.
            
            ### Example output
            
            ```json
            POST _analyze
            {
              "tokenizer": "standard",
              "text": "The 2 QUICK Brown-Foxes jumped over the lazy dog's bone."
            }
            ```
            
            ```json
            [ The, 2, QUICK, Brown, Foxes, jumped, over, the, lazy, dog's, bone ]
            ```
            
            ### Configuration
            
            - 허용되는 매개변수는 다음과 같다.
                - max_token_length
                    - 최대 토큰 길이
                    - 이 길이를 초과하는 토큰이 발견되면 max_token_length만큼 간격을 두고 분할된다.
                    - 기본 값은 255
    - UAX URL email
        
        ### UAX URL email tokenizer
        
        - uax_url_email 토크나이저는 URL과 이메일 주소를 단일 토큰으로 인식한다는 점을 제외하면 standard 토크나이저와 유사하다.
            
            ### Example output
            
            ```json
            POST _analyze
            {
              "tokenizer": "uax_url_email",
              "text": "Email me at john.smith@global-international.com"
            }
            ```
            
            ```json
            [ Email, me, at, john.smith@global-international.com ]
            ```
            
            ### Configuration
            
            - 허용되는 매개변수는 다음과 같다.
                - max_token_length
                    - 최대 토큰 길이
                    - 이 길이를 초과하는 토큰이 발견되면 max_token_length만큼 간격을 두고 분할된다.
                    - 기본 값은 255
    - Whitespace
        
        ### Whitespace tokenizer
        
        - whitespace 토크나이저는 공백 문자를 만날 때마다 텍스트를 용어로 나눈다.
            
            ### Example output
            
            ```json
            POST _analyze
            {
              "tokenizer": "whitespace",
              "text": "The 2 QUICK Brown-Foxes jumped over the lazy dog's bone."
            }
            ```
            
            ```json
            [ The, 2, QUICK, Brown-Foxes, jumped, over, the, lazy, dog's, bone. ]
            ```
            
            ### Configuration
            
            - 허용되는 매개변수는 다음과 같다.
                - max_token_length
                    - 최대 토큰 길이
                    - 이 길이를 초과하는 토큰이 발견되면 max_token_length만큼 간격을 두고 분할된다.
                    - 기본 값은 255
- Token filter reference
    - Apostrophe
        
        ### Apostrophe token filter
        
        - Apostrophe를 포함하여 Apostrophe 뒤의 모든 문자를 제거한다.
        - Elasitcsearh의 내장 터키어 분석기에 포함되어 있다.
        - 터키어용으로 제작된 Lucene의 ApostropheFilter를 사용한다.
    - ASCII folding
        
        ### ASCII folding token filter
        
        - 기본 라틴 유니코드 블록에 없는 알파벳, 숫자 및 기호 문다를 해당 ASCII 문자(있는 경우)로 반환한다.
        - Lucene의 ASCIIFoldingFilter를 사용한다.
    - CJK bigram
        
        ### CJK bigram token filter
        
        - CJK(중국어, 일본어, 한국어) 토큰으로 bigram을 형성한다.
        - Elasticsearch의 내장 CJK 언어 분석기에 포함되어 있다.
        - Lucene의 CJKBigramFilter를 사용한다.
    - CJK width
        
        ### CJK width token filter
        
        - CJK 문자의 너비 차이를 다음과 같이 정규화한다.
            - 전폭 ASCII 문자 변형을 동등한 기본 라틴 문자로 접는다.
            - 반자 가타카나 문자 변형을 해당 가나 문자로 접는다.
        - Elasticsearch의 내장 CJK 언어 분석기에 포함되어 있다.
        - Lucene의 CJKWidthFilter를 사용한다.
            - NFKC/NFKD 유니코드 정규화의 하위 집합으로 볼 수 있다.
    - Classic
        
        ### Classic token filter
        
        - Classic 토크나이저에서 생성된 용어의 선택적 사후 처리를 수행한다
        - 이 필터는 단어 끝에서 영어 소유격(’s)를 제거 하고 두문자어(acronyms)에서 점을 제거한다.
        - Lucenen의 ClassicFilter를 사용한다.
    - Common grams
        
        ### Common grams token filter
        
        - 지정된 일반 단어 집합에 대한 bigram을 생성한다.
        - 일반적인 단어를 완전히 무시하고 싶지 않은 경우 중지 토큰 필터 대신 common_grams 필터를 사용할 수 있다.
        - Lucene의 CommonGramsFilter를 사용한다.
    - Conditional
        
        ### Conditional token filter
        
        - 제공된 조건자 스크립트의 조건과 일치하는 토큰에 토큰 필터 세트를 적용한다.
        - Lucene의 ConditionalTokenFilter를 사용한다.
    - Decimal digit
        
        ### Decimal digit token filter
        
        - 유니코드 Decimal_Number 일반 범주의 모든 숫자를 0~9로 변환한다.
        - Lucene의 DecimalDigitFilter를 사용한다.
    - Delimited payload
        
        ### Delimited payload token filter
        
        - 이전 이름인 delimited_payload_filter는 더 이상 사용되지 않으며 새 인덱스와 함께 사용할 수 없다.
        - delimited_payload를 사용해야 한다
        - 지정된 구분 기호를 기반으로 토큰 스트림을 토큰과 페이로드로 분리한다.
        - Lucenen의 DelimitedPayloadTokenFilter를 사용한다.
    - Dictionary decompounder
        
        ### Dictionary decompounder token filter
        
        - 대부분의 경우 Dictionary decompounder token filter보다 더 빠른 hyphenation_decompounder token filter가 더 권장된다. 그러나 hyphenation_decompounder 필터를 구현하기 전에 dictionary_decompounder 필터를 사용하여 단어 목록의 품질을 확인할 수 있다
        - 지정된 단어 목록과 무차별 접근 방식을 사용하여 복합어에서 하위 단어를 찾는다.
        - 발견되면 이러한 하위 단어가 토큰 출력에 포함된다.
        - 게르만 언어용으로 구축된 Lucene의 DictionaryCompoundWordTokenFilter를 사용한다.
    - Edge n-gram
        
        ### Edge n-gram token filter
        
        - 토큰의 시작 부분부터 지정된 길이의 n-gram을 형성한다.
        - 사용자 지정하지 않은 경우 필터는 기본적으로 1자 가장자리 N-gram을 생성한다.
        - Lucenne의 EdgeNGramTokenFilter를 사용한다.
    - Elision
        
        ### Elision token filter
        
        - 토큰 시작 부분에서 지정된 제거(Elision)를 제거한다.
        - 사용자 정의하지 않으면 필터는 기본적으로 프랑스어 생략을 제거한다.
        - 사용자 정의 버전은 여러 Elasticsearch의 내장 언어 분석기에 포함되어 있다.
            - 카탈로니아어 분석기
            - 프랑스어 분석기
            - 아일랜드 분석기
            - 이탈리아 분석기
        - Lucene의 ElisionFilter를 사용한다.
    - Fingerprint
        
        ### Fingerprint token filter
        
        - 토큰 stream에서 중복 토큰을 정렬 및 제거한 다음 stream을 단일 출력 토큰으로 연결한다.
        - Fingerprint token filter에 의해 생성된 토큰은 텍스트 본문을 Fingerprinting 하고 clustering하는 데 유용하다.
        - Lucene의 FingerprintFilter를 사용한다.
    - Flattern graph
        
        ### Flattern graph token filter
        
        - Synony_graph 또는 word_delimiter_graph와 같은 그래프 토큰 필터에 의해 생성된 토큰 그래프를 평면화한다.
        - 다중 포지션 토큰을 포함하는 토큰 그래프를 평면화하면 그래프가 인덱싱에 적합해진다.
        - 평면화하지 않으면 다중 위치 토큰을 포함하는 토큰 그래프를 지원하지 않는다.
            - 단, 그래프를 평면화하는 것은 손실이 많은 프로세스다. 가능하면 flattern_graph 필터 사용은 피하는 편이 유리하다.
        - Lucene의 FlatternGraphFilter를 사용한다.
    - Hunspell
        
        ### Hunspell token filter
        
        - 제공된 Hunspell 사전을 기반으로 사전 형태소 분석을 제공한다.
        - hunspell 필터를 사용하려면 하나 이상의 언어별 Hunspell 사전 구성이 필요하다.
        - Lucene의 HunspellStermFilter를 사용한다.
    - Hyphenation decompounder
        
        ### Hyphenation decompounder token filter
        
        - XML 기반 하이픈 패턴을 사용하여 복합어에서 잠재적인 하위 단어를 찾는다.
        - 그 뒤 이러한 하위 단어를 지정된 단어 목록과 비교하여 확인한다.
        - 목록에 없는 하위 단어는 토큰 출력에서 제외된다.
        - Lucene의 HyphenationCompoundWorkTokenFilter를 사용한다.
    - Keep types
        
        ### Keep types token filter
        
        - 특정 유형의 토큰을 유지하거나 제거한다.
        - 토큰 유형은 토큰으로 변환할 때 토크나이저에 의해 설정된다.
        - Lucene의 TypeTokenFilter를 사용한다.
    - Keep words
        
        ### Keep Word token filter
        
        - 지정된 단어 목록에 포함된 토큰만 유지한다.
        - Lucene의 KeepWordFilter를 사용한다.
    - Keyword marker
        
        ### Keyword marker token filter
        
        - 지정된 토큰을 형태소 분석되지 않은 키워드로 표시한다.
        - keyword_marker 필터는 지정된 토큰에 true라는 keyword의 속성을 할당한다.
        - 형태소 분석기 또는 porter_stem과 같은 형태소 분석기 토큰 필터는 키워드 속성이 true인 토큰을 건너뛴다.
        - 분석기 구성의 형태소 분석기 토큰 필터 앞에 keyword_marker 필터가 나열되지 않으면 제대로 작동하지 않는다.
        - Lucene의 KeywordMarkerFilter를 사용한다.
    - Keyword repeat
        
        ### Keyword repeat token filter
        
        - stream에 있는 각 토큰의 키워드 버전을 출력한다. 키워드 토큰은 형태소 분석되지 않는다.
        - keyword_repeat 필터는 키워드 토큰에 true라는 keyword의 속성을 할당한다. 형태소 분석기 또는 porter_stem과 같은 형태소 분석기 토큰 필터는 키워드 속성이 true인 토큰을 건너뛴다.
        - 형태소 분석기 토큰 필터와 함께 keyword_repeat 필터를 사용하여 스트림에 있는 각 토큰의 형태소 및 형태소 제거 버전을 출력할 수 있다.
        - Lucene의 KeywordRepeatFilter를 사용한다.
    - KStem
        
        ### KStem token filter
        
        - 영어에 대한 KStem 기반 형태소 분석을 제공한다. ksterm 필터는 알고리즘 형태소 분석과 내장 사전을 결합한다
        - ksterm 필터는 porter_stem 필터와 같은 다른 영어 형태소 분석기 필터보다 덜 공격적으로 형태소 분석하는 경향이 있다.
        - kstem 필터는 형태소 분석기 필터의 light_english 변형과 동일하다
        - Lucene의 KStemFilter를 사용한다.
    - Length
        
        ### Length token filter
        
        - 지정된 문자 길이보다 짧거나 긴 토큰을 제거한다.
        - Lucene의 LengthFilter를 사용한다.
        - length 필터는 전체 토큰을 제거한다. 토큰을 특정 길이로 줄이려면 truncate 필터를 사용해야 한다.
    - Limit token count
        
        ### Limit token count token filter
        
        - 출력 토큰의 수를 제한한다. limit 필터는 일반적으로 토큰 수를 기준으로 문서 필드 값의 크기를 제한하는 데 사용된다.
        - 기본적으로 limit 필터는 stream의 첫 번째 토큰만 유지한다.
        - Lucene의 LimitTokenCountFilter를 사용한다.
    - Lowercase
        
        ### Lowercase token filter
        
        - 토큰 텍스트를 소문자로 변경한다.
        - 기본 필터 외에도 lowercase 토큰 필터는 그리스어, 아일랜드어, 터키어에 대한 Lucene의 언어별 소문자 필터에 대한 액세스를 제공한다
    - Minhash
        
        ### MinHash token filter
        
        - MinHash 기술을 사용하여 토큰 stream에 대한 서명을 생성한다.
        - MinHash 서명을 사용하여 문서의 유사성을 추정한다.
        - min_hash 필터는 토큰 stream에서 다음 작업을 순서대로 수행한다
            - stream의 각 토큰을 hash한다
            - 각 bucket의 가장 작은 hash만 유지하면서 hash를 bucket에 할당한다
            - 각 bucket에서 가장 작은 hash를 토큰 stream으로 출력한다
        - Lucene의 MinHashFilter를 사용한다.
    - Multiplexer
        
        ### Multiplexer token filter
        
        - multiplexer 토큰 필터는 동일한 위치에서 여러 토큰을 방출하며, 각 토큰 버전은 서로 다른 필터를 통해 실행된다.
        - 동일한 출력 토큰은 제거된다.
        - 들어오는 토큰 stream에 중복 토큰이 있는 경우도 멀티플렉서에 의해 제거된다.
    - N-gram
        
        ### N-gram token filter
        
        - 토큰에서 지정된 길이의 n-gram을 형성한다
        - Lucene의 NGramTokenFilter를 사용한다.
    - Normalization
        
        ### Normalization token filter
        
        - 특정 언어의 특수 문자를 정규화하는 데 사용할 수 있다.
            - 아라비아 말 : arabic_normalization
            - 독일 말 : german_normalization
            - 힌디 어 : hindi_normalization
            - 인도어 : indic_normalization
            - 쿠르드어(소라니어) : sorani_normalization
            - 페이스아 어 : persian_normalization
            - 스칸디나비아 어 : scandinavian_normalization, scandinavian_folding
            - 세르비아 어 : servian_normalization
    - Pattern capture
        
        ### Pettern capture token filter
        
        - pettern 토크나이저와 달리 정규식의 모든 캡처 그룹에 대해 토큰을 내보낸다.
        - 패턴은 문자열의 시작과 끝에 고정되어 있지 않으므로 각 패턴이 여러 번 일치할 수 있으며 일치 항목이 겹칠 수 있다.
    - Pattern replace
        
        ### Pattern replace token filter
        
        - 정규식을 사용하여 토큰 하위 문자열을 일치시키고 변경한다
        - Java의 정규식 구문을 사용한다.
        - 기본적으로 필터는 일치하는 하위 문자열을 빈 하위 문자열(””)로 변경한다.
        - 대체 하위 문자열은 Java $g 구문을 사용하여 원래 토큰 텍스트의 캡처 그룹을 참조할 수 있다.
        - Lucene의 PatternReplaceFilter를 사용한다.
    - Phonetic
        
        ### Phonetic token filter
        
        - analysis-phonetic 플러그인으로 제공된다.
    - Porter stem
        
        ### Porter stem token filter
        
        - Porter 형태소 분석 알고리즘을 기반으로 영어에 대한 알고리즘 형태소 분석을 제공한다.
        - 다른 영어 형태소 분석기 필터보다 더 공격적으로 형태소 분석을 수행한다
        - stemer 필터의 english 변형과 동일하다
        - Lucene의 PorterStemFilter를 사용한다.
    - Predicate script
        
        ### Predicate script token filter
        
        - 제공된 predicate 스크립트와 일치하지 않는 토큰을 제거한다.
        - 인라인 Painless 스크립트로만 지원한다.
        - 스크립트는 분석 predicate context에서 평가된다.
    - Remove duplicates
        
        ### Remove duplicates token filter
        
        - 같은 위치에 중복된 토큰을 제거한다.
        - Lucene의 RemoveDuplicatesTokenFilter를 사용한다.
    - Reverse
        
        ### Reverse token filter
        
        - stream의 각 토큰을 반전한다.
        - 역방향 토큰은 -ion으로 끝나는 단어를 찾거나 확장자로 파일 이름을 검색하는 등 접미사 기반 검색에 유용하다.
        - Lucene의 ReverseStringFilter를 사용한다.
    - Shingle
        
        ### Shingle token filter
        
        - 인접한 토큰을 연결하여 토큰 stream에 shingle 또는 단어 n-gram을 추가한다.
        - 기본적으로 shingle 토큰 필터는 두 단어로 된 shingle과 unigram을 출력한다
        - Lucene의 ShingleFilter를 사용한다.
    - Snowball
        
        ### Snowball token filter
        
        - Snowball에서 생성된 형태소 분석기를 사용하여 단어를 형태소 분석하는 필터
        - language 매개변수는 여러 사용 가능한 값을 사용하여 형태소 분석기를 제어한다.
    - Stemmer
        
        ### Stemmer token filter
        
        - 여러 언어에 대한 알고리즘 형태소 분석을 제공하며 일부 언어에는 추가 변형이 포함된다.
        - 사용자 정의되지 않은 경우 필터는 영어에 대해 porter 형태소 분석 알고리즘을 사용한다.
    - Stemmer override
        
        ### Stemmer override token filter
        
        - 사용자 지정 mapping을 적용한 다음 형태소 분석기에 의해 이러한 용어가 수정되지 않도록 보호하여 형태소 분석 알고리즘을 재정의한다
        - 형태소 분석 필터 앞에 배치해야 한다.
    - Stop
        
        ### Stop token filter
        
        - 토큰 stream에서 불요어를 제거한다
        - 영어 외에도 필터는 여러 언어에 대해 사전 정의된 불용어 목록을 지원한다.
        - 또한 자신만의 중지 단어를 배열이나 파일로 지정할 수 있다
        - Lucene의 StopFilter를 사용한다.
    - Synonym
        
        ### Synonym token filter
        
        - 분석 프로세스 중에 동의어를 쉽게 처리할 수 있다.
        - 동의어는 구성 파일을 사용하여 구성한다.
    - Synonym graph
        
        ### Synonym graph token filter
        
        - 분석 과정에서 다중 단어 동의어를 포함한 동의어를 쉽게 처리할 수 있다.
        - 여러 단어로 구성된 동의어를 적절하게 처리하기 위해 이 토큰 필터는 처리 중에 그래프 토큰 stream을 생성한다.
        - 검색 분석기의 일부로만 사용되도록 설계되었다. 색인 생성 중에 동의어를 적용하려면 standard synonym token filter를 사용해야 한다.
    - Trim
        
        ### Trim token filter
        
        - 스트림의 각 토큰에서 선행 및 후행 공백을 제거한다.
        - 토큰의 길이를 변경할 수 있지만 trim 필터는 토큰의 오프셋을 변경하지 않는다.
        - Lucene의 TrimFilter를 사용한다.
    - Truncate
        
        ### Truncate token filter
        
        - 지정된 문자 제한을 초과하는 토큰을 자른다.
        - 기본 값은 10이지만 length 매개변수를 사용하여 사용자 정의할 수 있다
        - Lucene의 TruncateTokenFilter를 사용한다.
    - Unique
        
        ### Unique token filter
        
        - stream에서 중복 토큰을 제거한다.
        - only_on_same_position 매개변수를 true로 설정하면 필터는 동일한 위치에 있는 unique 중복 토큰만 제거한다.
    - Uppercase
        
        ### Uppercase token filter
        
        - 토큰 텍스트를 대문자로 변경한다
        - Lucene의 UpperCaseFilter를 사용한다.
        - 언어에 따라 대문자는 여러 소문자로 mapping되며 소문자 정보가 손실될 수 있다.
    - Word delimiter
        
        ### Word delimiter token filter
        
        - word delimiter 대신 word delimiter graph 토큰 필터가 권장된다.
        - word delimiter 필터는 잘못된 토큰 그래프를 생성할 수 있다
        - deprecate 된 Lucene의 WordDelimiterFilter를 사용한다.
        - 영문 숫자가 아닌 문자로 토큰을 분할한다. 필터가 사용하는 규칙은 다음과 같다.
            - 영문 숫자가 아닌 문자로 토큰을 구분한다.
            - 각 토큰에서 선행 또는 후행 구분 기호를 제거한다
            - 대소문자 전환 시 토큰을 분할한다
            - 문자-숫자 전환 시 토큰을 분할한다
            - 각 토큰의 끝에서 영어 소유격(’s)을 제거한다.
    - Word delimiter graph
        
        ### Word delimiter graph
        
        - 영문 숫자가 아닌 문자로 토큰을 분할한다.
        - 필터가 사용하는 규칙은 다음과 같다
            - 영문 숫자가 아닌 문자로 토큰을 분할한다
            - 각 토큰에서 선행 또는 후행 기호를 제거한다
            - 대소문자 전환 시 토큰을 분할한다
            - 문자-숫자 전환 시 토큰을 분할한다
            - 각 토큰의 끝에서 영어 소유격(’s)를 제거한다.
        - Lucene의 WordDelimiterGraphFilter를 사용한다.
- Character filters reference
    - Character filters reference
        
        ### Character filters reference
        
        - 문자 필터는 문자 stream이 토크나이저에 전달되기 전에 사전 처리하는 데 사용된다.
        - 문자 필터는 원본 텍스트를 문자 stream으로 수신하고 문자를 추가, 제거 또는 변경하여 stream을 변환할 수 있다
    - HTML strip
        
        ### HTML Strip character filter
        
        - 텍스트에서 HTML 요소를 제거하고 HTML entities를 디코딩된 값으로 변경한다.
            - ⇒ &amp를 &로 변경
        - Lucene의 HTMLStripCharFilter를 사용한다.
    - Mapping
        
        ### Mapping character filter
        
        - 키와 값의 맵을 허용한다. 키와 동일한 문자열을 발견할 때마다 해당 키와 관련된 값으로 대체한다.
        - 주어진 지점에서 가장 긴 패턴 일치가 승리하며, 대체 항목은 빈 문자열이 될 수 있다.
        - Lucene의 MappingCharFilter를 사용한다.
    - Pattern replace
        
        ### Pattern replace character filter
        
        - 정규식을 사용하여 지정된 대체 문자열로 대체되어야 하는 문자를 일치시킨다.
        - 대체 문자열은 정규식의 캡처 그룹을 참조할 수 있다.
- Normalizers
    
    ### Normalizers
    
    - 노멀라이저는 단일 토큰만 내보낼 수 있다는 점을 제외하면 분석기와 유사하다. 결과적으로 토크나이저가 없으며 사용 가능한 문자 필터 및 토큰 필터의 하위 집합만 허용한다.
    - 문자별로 작동하는 필터만 허용된다.
    - Elasticsearch에는 lowercase 노멀라이저가 내장되어 있으며 다른 형태의 정규화에는 사용자 정의 구성이 필요하다.
        
        ### Custom normalizers
        
        - 사용자 정의 노멀라이저는 문자 필터 목록과 토큰 필터 목록을 사용한다.
            
            ```json
            PUT index
            {
              "settings": {
                "analysis": {
                  "char_filter": {
                    "quote": {
                      "type": "mapping",
                      "mappings": [
                        "« => \"",
                        "» => \""
                      ]
                    }
                  },
                  "normalizer": {
                    "my_normalizer": {
                      "type": "custom",
                      "char_filter": ["quote"],
                      "filter": ["lowercase", "asciifolding"]
                    }
                  }
                }
              },
              "mappings": {
                "properties": {
                  "foo": {
                    "type": "keyword",
                    "normalizer": "my_normalizer"
                  }
                }
              }
            }
            ```
            

# Index templates

- Index templates
    
    ### Index templates
    
    - 인덱스 템플릿은 인덱스가 생성될 때 Elasticsearch에 인덱스를 구성하는 방법을 알려주는 방법이다.
    - 데이터 stream의 경우 인덱스 템플릿은 stream이 생성될 때 stream의 지원 인덱스를 구성한다.
    - 템플릿은 인덱스 생성 전에 구성된다.
    - 수동으로 또는 문서 색인화를 통해 색인이 생성되면 템플릿 설정이 색인 생성을 위한 기초로 사용된다.
    - 템플릿에는 인덱스 템플릿과 구성 요소 템플릿 두 가지 유형이 있다.
        - 구성 요소 템플릿
            - 매핑, 설정, 별칭을 구성하는 재사용 가능한 구성 요소
            - 구성 요소 템플릿을 사용하여 인덱스 템플릿을 구성할 수 있지만 인덱스 집합에 직접 적용되지는 않는다.
        - 인덱스 템플릿
            - 구성 요소 템플릿이 포함되며 설정, 매핑, 별칭을 직접 지정할 수도 있다.
            - Composable 템플릿은 기존 템플릿보다 우선 적용된다. 특정 색인과 일치하는 구성 가능한 템플릿이 없는 경우 기존 템플릿이 계속 일치하고 적용될 수 있다
            - 인덱스 템플릿 자체에 지정된 설정은 해당 구성 요소 템플릿의 설정보다 우선한다
            - 새 데이터 스트림 또는 인덱스가 둘 이상의 인덱스 템플릿과 일치하는 경우 우선 순위가 가장 높은 인덱스 템플릿이 사용된다.
        
        ### Create index template
        
        - 인덱스 템플릿을 사용 하고 구성 요소 템플릿 API를 넣어 인덱스 템플릿을 생성하고 업데이트한다.
        - Kibana의 스택 관리에서 인덱스 템플릿을 관리할 수도 있다.
            
            ```json
            PUT _component_template/component_template1
            {
              "template": {
                "mappings": {
                  "properties": {
                    "@timestamp": {
                      "type": "date"
                    }
                  }
                }
              }
            }
            
            PUT _component_template/runtime_component_template
            {
              "template": {
                "mappings": {
                  "runtime": { 
                    "day_of_week": {
                      "type": "keyword",
                      "script": {
                        "source": "emit(doc['@timestamp'].value.dayOfWeekEnum.getDisplayName(TextStyle.FULL, Locale.ROOT))"
                      }
                    }
                  }
                }
              }
            }
            ```
            
            ```json
            PUT _index_template/template_1
            {
              "index_patterns": ["te*", "bar*"],
              "template": {
                "settings": {
                  "number_of_shards": 1
                },
                "mappings": {
                  "_source": {
                    "enabled": true
                  },
                  "properties": {
                    "host_name": {
                      "type": "keyword"
                    },
                    "created_at": {
                      "type": "date",
                      "format": "EEE MMM dd HH:mm:ss Z yyyy"
                    }
                  }
                },
                "aliases": {
                  "mydata": { }
                }
              },
              "priority": 500,
              "composed_of": ["component_template1", "runtime_component_template"], 
              "version": 3,
              "_meta": {
                "description": "my custom"
              }
            }
            ```
            
- Simulate multi-component templates
    
    ### Simulate multi-component templates
    
    - 템플릿은 여러 구성 요소 템플릿뿐만 아니라 인덱스 템플릿 자체로도 구성될 수 있으므로 결과 인덱스 설정이 무엇인지 결정하는 두 가지 시뮬레이션 API가 있다.
    - 특정 인덱스 이름에 적용될 설정을 시뮬레이션하려면 다음을 수행한다
        
        ```json
        POST /_index_template/_simulate_index/my-index-000001
        ```
        
    - 기존 템플릿에서 적용될 설정을 시뮬레이션하려면 다음을 수행한다
        
        ```json
        POST /_index_template/_simulate/template_1
        ```
        
    - 요청 시 템플릿 정의를 지정할 수도 있다.
        
        ```json
        PUT /_component_template/ct1
        {
          "template": {
            "settings": {
              "index.number_of_shards": 2
            }
          }
        }
        
        PUT /_component_template/ct2
        {
          "template": {
            "settings": {
              "index.number_of_replicas": 0
            },
            "mappings": {
              "properties": {
                "@timestamp": {
                  "type": "date"
                }
              }
            }
          }
        }
        
        POST /_index_template/_simulate
        {
          "index_patterns": ["my*"],
          "template": {
            "settings" : {
                "index.number_of_shards" : 3
            }
          },
          "composed_of": ["ct1", "ct2"]
        }
        ```
        
- Config ignore_missing_component_templates
    
    ### Config ignore_missing_component_templates
    
    - ignore_missing_component_templates 구성 옵션은 인덱스 템플릿이 존재하지 않을 수 있는 구성 요소 템플릿을 참조하는 경우 사용할 수 있다.
    - 인덱스 템플릿을 기반으로 데이터 스트림이 생성될 때마다 구성 요소 템플릿의 존재 여부를 확인한다
    - 존재하는 경우 인덱스의 복합 설정을 구성하는 데 사용되며, 존재하지 않을 경우 무시된다.
        
        ### Usage Example
        
        - 구성 요소 템플릿을 먼저 만든다. 선택 사항이 아니며 인덱스 템플릿보다 먼저 생성되어야 한다.
            
            ```json
            PUT _component_template/logs-foo_component1
            {
              "template": {
                "mappings": {
                  "properties": {
                    "host.name": {
                      "type": "keyword"
                    }
                  }
                }
              }
            }
            ```
            
        - 그 뒤 인덱스 템플릿을 생성한다.
            
            ```json
            "composed_of": ["logs-foo_component1", "logs-foo_component2"]
            ```
            
        - 누락된 구성 요소 템플릿을 추가할 때는 다음과 같이 수행한다.
            
            ```json
            "ignore_missing_component_templates": ["logs-foo_component2"],
            ```
            
        - ignore_missing_component_templates를 통해 템플릿을 생성하는 동안 해당 구성 요소 템플릿의 존재 여부를 확인하지 않게 할 수 있다.
            
            ```json
            PUT _index_template/logs-foo
            {
              "index_patterns": ["logs-foo-*"],
              "data_stream": { },
              "composed_of": ["logs-foo_component1", "logs-foo_component2"],
              "ignore_missing_component_templates": ["logs-foo_component2"],
              "priority": 500
            }
            ```
            
        - 색인 템플릿이 정상적으로 생성되었으며, 이 템플릿을 기반으로 데이터 stream을 생성할 수 있다.

# Data streams

- Data streams
    
    ### Data streams
    
    - 데이터 스트림을 사용하면 여러 인덱스에 걸쳐 추가 전용 시계열 데이터를 저장하는 동시에 요청에 대해 명명된 단일 resource를 제공할 수 있다. 데이터 스트림은 로그, 이벤트, 지표 및 기타 지속적으로 생성되는 데이터에 적합하다.
    - 인덱싱 및 검색 요청을 데이터 스트림에 직접 제출할 수 있다. 스트림은 스트림의 데이터를 저장하는 지원 인덱스로 요청을 자동으로 라우팅한다. ILM(인덱스 수명 주기 관리)을 사용하여 지원 인덱스 관리를 자동화할 수 있다.
    - ILM은 데이터가 증가함에 따라 비용과 오버헤드를 줄이는 데 도움이 된다.
        
        ### Backing indices
        
        - 데이터 스트림은 하나 이상의 숨겨진 자동 생성 지원 인덱스로 구성된다.
        - 데이터 스트림에는 일치하는 색인 템플릿이 필요하다. 템플릿에는 스트림의 지원 인덱스를 구성하는 데 사용되는 매핑과 설정이 포함되어 있다.
        - 데이터 스트림에 인덱싱된 모든 문서에는 date, date_nanos 필드 유형으로 매핑된 @timestamp 필드가 포함되어야 한다. 인덱스 템플릿이 @timesstamp 필드에 대한 매핑을 지정하지 않으면 Elasticsearch는 @timestamp를 기본 옵션이 있는 날짜 필드로 매핑한다.
        - 여러 데이터 스트림에 동일한 인덱스 템플릿을 사용할 수 있다. 데이터 스트림에서 사용 중인 인덱스 템플릿은 삭제할 수 없다.
        - 지원 인덱스의 이름 패턴은 구현 세부 사항이며 여기에서 인텔리전스가 파생되어서는 안 된다. 유일한 불변성은 각 데이터 스트림 생성 인덱스가 고유한 이름을 갖는다는 것이다.
        
        ### Read requests
        
        - 데이터 스트림에 읽기 요청을 제출하면 스트림은 요청을 모든 지원 인덱스로 라우팅한다.
            
            ![Untitled](Elastic_Guide(8.9)/Untitled%202.png)
            
        
        ### Write index
        
        - 가장 최근에 생성된 지원 인덱스는 데이터 스트림의 쓰기 인덱스다. 스트림은 이 인덱스에만 새 문서를 추가한다.
            
            ![Untitled](Elastic_Guide(8.9)/Untitled%203.png)
            
        - 인덱스에 직접 요청을 보내더라도 다른 지원 인덱스를 새 문서에 추가할 수 없다.
        - 또한 clone, delete, shrink, split과 같은 쓰기 인덱스에 대한 작업을 수행할 수 없다.
        
        ### Rollover
        
        - Rollover는 스트림의 새 쓰기 인덱스가 되는 새 지원 인덱스를 생성한다.
        - 쓰기 인덱스가 지정된 수명이나 크기에 도달하면 ILM을 사용하여 데이터 스트림을 자동으로 rollover 하는 것이 좋다. 필요한 경우 데이터 스트림을 수동으로 rollover할 수도 있다.
        
        ### Generation
        
        - 각 데이터 스트림은 해당 생성을 추적한다. 생성되는 것은 000001 에서 시작하는 0으로 채워지는 6자리 정수다.
        - 지원 인덱스가 생성되면 다음 규칙을 사용하여 인덱스 이름이 지정된다.
            
            ```json
            .ds-<data-stream>-<yyyy.MM.dd>-<generation>
            ```
            
        - 축소 또는 복원과 같은 일부 작업은 지원 인덱스의 이름을 변경할 수 있다. 이름 변경은 데이터 스트림에서 지원 인덱스를 제거하지 않는다.
        - 데이터 스트림의 생성은 데이터 스트림에 새 인덱스를 추가하지 않고도 변경될 수 있다.
        
        ### Append-only
        
        - 데이터 스트림은 기존 데이터가 거의 업데이트되지 않는 사용 사례를 위해 설계되었다.
        - 기존 문서에 대한 업데이트 또는 삭제 요청을 데이터 스트림에 직접 보낼 수 없다. 대신 쿼리 API를 통해 업데이트 및 삭제를 사용해야 한다.
- Set up a data stream
    - Fleet 혹은 Elastic Agent는 직접 데이터 스트림을 설정한다.
        
        ### Create an index lifecycle policy
        
        - 선택 사항이지만 ILM을 사용하여 데이터 스트림의 백업 인덱스 관리를 자동화하는 것이 권장된다. ILM에는 인덱스 수명 주기 정책이 필요하다.
        - create lifecycle policy API를 사용할 수도 있다.
            
            ```json
            PUT _ilm/policy/my-lifecycle-policy
            {
              "policy": {
                "phases": {
                  "hot": {
                    "actions": {
                      "rollover": {
                        "max_primary_shard_size": "50gb"
                      }
                    }
                  },
                  "warm": {
                    "min_age": "30d",
                    "actions": {
                      "shrink": {
                        "number_of_shards": 1
                      },
                      "forcemerge": {
                        "max_num_segments": 1
                      }
                    }
                  },
                  "cold": {
                    "min_age": "60d",
                    "actions": {
                      "searchable_snapshot": {
                        "snapshot_repository": "found-snapshots"
                      }
                    }
                  },
                  "frozen": {
                    "min_age": "90d",
                    "actions": {
                      "searchable_snapshot": {
                        "snapshot_repository": "found-snapshots"
                      }
                    }
                  },
                  "delete": {
                    "min_age": "735d",
                    "actions": {
                      "delete": {}
                    }
                  }
                }
              }
            }
            ```
            
        
        ### Create component templates
        
        - 데이터 스트림에는 일치하는 인덱스 템플릿이 필요하다. 대부분의 경우 하나 이상의 구성 요소 템플릿을 사용하여 이 인덱스 템플릿을 구성한다.
        - 일반적으로 매핑 및 인덱스 설정에는 별도의 구성 요소 템플릿을 사용한다. 이를 통해 여러 인덱스 템플릿에서 구성 요소 템플릿을 재사용할 수 있다.
        - 구성 요소 템플릿에는 다음이 포함되어야 한다.
            - @timestamp 필드에 대한 date 또는 date_nanos 매핑. 매핑을 지정하지 않으면 @timestamp를 기본 옵션이 있는 날짜 필드로 매핑한다.
            - index.lifecycle.name. 인덱스 설정의 생명주기 정책
        - 필드를 매핑할 때 Elastic Common Schema(ECS) 사용이 권장된다. ECS 필드는 기본적으로 여러 Elastic Stack 기능과 통합된다.
        - 구성요소 템플릿 API 생성을 사용할 수도 있다.
            
            ```json
            # Creates a component template for mappings
            PUT _component_template/my-mappings
            {
              "template": {
                "mappings": {
                  "properties": {
                    "@timestamp": {
                      "type": "date",
                      "format": "date_optional_time||epoch_millis"
                    },
                    "message": {
                      "type": "wildcard"
                    }
                  }
                }
              },
              "_meta": {
                "description": "Mappings for @timestamp and message fields",
                "my-custom-meta-field": "More arbitrary metadata"
              }
            }
            
            # Creates a component template for index settings
            PUT _component_template/my-settings
            {
              "template": {
                "settings": {
                  "index.lifecycle.name": "my-lifecycle-policy"
                }
              },
              "_meta": {
                "description": "Settings for ILM",
                "my-custom-meta-field": "More arbitrary metadata"
              }
            }
            ```
            
        
        ### Create an index template
        
        - 구성 요소 템플릿을 사용하여 색인 템플릿을 만든다
            - 데이터 스트림의 이름과 일치하는 하나 이상의 인덱스 패턴. 데이터 스트림 명명 체계를 사용하는 것이 권장된다.
            - 템플릿에 데이터 스트림 활성화
            - 매핑 및 인덱스 설정이 포함된 모든 구성 요소 템플릿
            - 내장 템플릿과의 충돌을 피하기 위해 우선 순위가 200보다 높을 것.
        - 인덱스 템플릿 생성 API를 사용할 수도 있다.
        - 데이터 스트림을 활성화하려면 data_stream 객체를 포함해야 한다.
            
            ```json
            PUT _index_template/my-index-template
            {
              "index_patterns": ["my-data-stream*"],
              "data_stream": { },
              "composed_of": [ "my-mappings", "my-settings" ],
              "priority": 500,
              "_meta": {
                "description": "Template for my time series data",
                "my-custom-meta-field": "More arbitrary metadata"
              }
            }
            ```
            
        
        ### Create the data stream
        
        - 인덱싱 요청은 데이터 스트림에 문서를 추가한다. 이러한 요청은 create나 op_type을 사용해야 한다. 문서에는 @timestamp 필드가 포함되어야 한다.
        - 데이터 스트림을 자동으로 생성하려면 스트림 이름을 대상으로 하는 인덱스 생성 요청을 제출해야 한다. 이 이름은 인덱스 템플릿의 인덱스 패턴 중 하나와 일치해야 한다.
            
            ```json
            PUT my-data-stream/_bulk
            { "create":{ } }
            { "@timestamp": "2099-05-06T16:21:15.000Z", "message": "192.0.2.42 - - [06/May/2099:16:21:15 +0000] \"GET /images/bg.jpg HTTP/1.0\" 200 24736" }
            { "create":{ } }
            { "@timestamp": "2099-05-06T16:25:42.000Z", "message": "192.0.2.255 - - [06/May/2099:16:25:42 +0000] \"GET /favicon.ico HTTP/1.0\" 200 3638" }
            
            POST my-data-stream/_doc
            {
              "@timestamp": "2099-05-06T16:21:15.000Z",
              "message": "192.0.2.42 - - [06/May/2099:16:21:15 +0000] \"GET /images/bg.jpg HTTP/1.0\" 200 24736"
            }
            ```
            
        - 데이터 스트림 생성 API를 사용하여 수동으로 스트림을 생성할 수도 있다. 스트림의 이름은 템플릿의 인덱스 패턴 중 하나와 계속 일치해야 한다.
            
            ```json
            PUT _data_stream/my-data-stream
            ```
            
        
        ### Secure the data stream
        
        - 인덱스 권한을 사용하여 데이터 스트림에 대한 액세스를 제어한다. 데이터 스트림에 대한 권한을 부여하면 해당 지원 인덱스에 대해 동일한 권한이 부여된다.
        
        ### Convert  an index alias to a data stream
        
        - Elasticsearch 7.9 이전에는 일반적으로 쓰기 인덱스와 함께 인덱스 별칭을 사용하여 시계열 데이터를 관리했다. 데이터 스트림은 이 기능을 대체하고 유지 관리가 덜 필요하며 데이터 계층과 자동으로 통합된다.
        - 쓰기 인덱스가 있는 인덱스 별칭을 동일한 이름의 데이터 스트림으로 변환하려면 데이터 스트림으로 마이그레이션 API를 사용할 수 있다.
            
            ```json
            POST _data_stream/_migrate/my-time-series-data
            ```
            
        
        ### Get information about a data stream
        
        - Kibana의 데이터 스트림에 대한 정보는 스택 관리 > 인덱스 관리에서 확인 가능하다.
        - 데이터 스트림 보기에서 데이터 스트림의 이름을 클릭한다.
        - 데이터 스트림 가져오기 API를 사용할 수도 있다.
            
            ```json
            GET _data_stream/my-data-stream
            ```
            
        
        ### Delete a data stream
        
        - Kibana에서 스택 관리 > 인덱스 관리로 이동, 데이터 스트림과 해당 백업 인덱스를 삭제할 수 있다. 데이터 스트림 보기에서 휴지통 아이콘을 눌러 삭제할 수 있으며, 이 휴지통 아이콘은 데이터 스트림에 대한 delete_index 보안 권한이 있는 경우에만 표시된다.
        - 데이터 스트림 삭제 API를 사용할 수도 있다.
            
            ```json
            DELETE _data_stream/my-data-stream
            ```
            
- Use a data stream
    - 데이터 스트림을 설정한 후 사용할 수 있는 기능은 다음과 같다.
        
        ### Add documents to a data stream
        
        - 개별 문서를 추가할 때는 index API를 사용할 수 있다. 수집 파이프라인이 지원된다.
            
            ```json
            POST /my-data-stream/_doc/
            {
              "@timestamp": "2099-03-08T11:06:07.000Z",
              "user": {
                "id": "8a4f500d"
              },
              "message": "Login successful"
            }
            ```
            
        
        ### Search a data stream
        
        - data streams가 지원되는 검색 API는 다음과 같다.
            - Search
            - Async search
            - Multi search
            - Field capabilities
            - EQL search
        
        ### Get statistics for a data stream
        
        - 데이터 스트림 통계 API를 사용하여 하나 이상의 데이터 스트림에 대한 통계를 가져온다.
        
        ```json
        GET /_data_stream/my-data-stream/_stats?human=true
        ```
        
        ### Manually roll over a data stream
        
        - 롤오버 API를 사용하여 데이터 스트림을 수동으로 롤오버 할 수 있다.
            
            ```json
            POST /my-data-stream/_rollover/
            ```
            
        
        ### Open closed backing indices
        
        - 데이터 스트림을 검색하더라도 폐쇄형 지원 인덱스는 검색할 수 없다. 또한, 닫힌 인덱스의 문서는 업데이트하거나 삭제할 수 없다.
        - 폐쇄형 지원 인덱스를 다시 열려면 오픈 인덱스 API 요청을 인덱스에 직접 제출해야 한다.
            
            ```json
            POST /.ds-my-data-stream-2099.03.07-000001/_open/
            ```
            
        - 모든 폐쇄형 지원 인덱스를 다시 열려면 스트림에 공개 인덱스 API 요청을 제출해야 한다.
            
            ```json
            POST /my-data-stream/_open/
            ```
            
        
        ### Reindex with a data stream
        
        - reindex API를 사용하여 기존 인덱스, 별칭 또는 데이터 스트림의 문서를 데이터 스트림으로 복사한다. 데이터 스트림은 추가 전용이므로 데이터 스트림에 대한 재색인은 create나 op_type을 사용해야 한다.
        - 재색인은 데이터 스트림의 기존 문서를 업데이트할 수 없다.
            
            ```json
            POST /_reindex
            {
              "source": {
                "index": "archive"
              },
              "dest": {
                "index": "my-data-stream",
                "op_type": "create"
              }
            }
            ```
            
        
        ### Update documents in a data stream by query
        
        - 제공된 쿼리와 일치하는 데이터 스트림의 문서를 업데이트할 때는 쿼리 별 업데이트 API를 사용한다.
            
            ```json
            POST /my-data-stream/_update_by_query
            {
              "query": {
                "match": {
                  "user.id": "l7gk7f82"
                }
              },
              "script": {
                "source": "ctx._source.user.id = params.new_id",
                "params": {
                  "new_id": "XgdX0NoX"
                }
              }
            }
            ```
            
        
        ### Delete documents in a data stream by query
        
        - 제공된 쿼리와 일치하는 데이터 스트림의 문서를 삭제하려면 쿼리별 삭제 API를 사용한다.
            
            ```json
            POST /my-data-stream/_delete_by_query
            {
              "query": {
                "match": {
                  "user.id": "vlb44hny"
                }
              }
            }
            ```
            
        
        ### Update or delete documents in a backing index
        
        - 문서가 포함된 지원 인덱스에 요청을 보내 데이터 스트림의 문서를 업데이트하거나 삭제할 수 있다. 다음이 필요하다
            - 문서 ID
            - 문서를 포함하는 백업 인덱스의 이름
            - 문서를 업데이트하는 경우 일련 번호 및 기본 용어
- Modify a data stream
    
    ### Change mappings and settings for a data stream
    
    - 각 데이터 스트림에는 일치하는 색인 템플릿이 있다. 이 템플릿의 매핑 및 인덱스 설정은 스트림에 대해 생성된 새 지원 인덱스에 적용된다. 여기에는 스트림이 생성될 때 자동 생성되는 스트림의 첫 번째 지원 인덱스가 포함된다.
    - 추후 데이터 스트림의 매핑이나 설정을 변경해야 하는 경우 몇 가지 옵션이 있다.
        - 데이터 스트림에 새 필드 매핑 추가
        - 데이터 스트림의 기존 필드 매핑 변경
        - 데이터 스트림에 대한 동적 인덱스 설정 변경
        - 데이터 스트림에 대한 정적 인덱스 설정 변경
        
        ### Add a new field mapping to a data stream
        
        1. 데이터 스트림에서 사용되는 인덱스 템플릿 업데이트. 이로 인해 스트림에 대해 생성된 향후 지원 인덱스에 새 필드 매핑이 추가된다. 
        2. 업데이트 매핑 API를 사용하여 데이터 스트림에 새 필드 매핑을 추가. 기본적으로 이는 쓰기 인덱스를 포함하여 스트림의 기존 지원 인덱스에 매핑을 추가한다. 
        
        ### Change an existing field mapping in a data stream
        
        1. 데이터 스트림에서 사용되는 인덱스 템플릿 업데이트. 이로 인해 업데이트된 필드 매핑이 스트림에 대해 생성된 향후 지원 인덱스에 추가된다. 
        2. 업데이트 매핑 API를 사용하여 데이터 스트림에 매핑 변경 사항을 적용한다. 이는 쓰기 인덱스를 포함하여 스트림의 기존 지원 인덱스에 변경 사항을 적용한다. 
        - 지원되는 매핑 매개 변수를 제외하고 데이터 스트림의 일치하는 인덱스 템플릿이나 해당 지원 인덱스에서도 기존 필드의 매핑 또는 필드 데이터 유형을 변경하지 않는 것이 좋다. 기존 필드의 매핑을 변경하면 이미 인덱싱된 데이터가 무효화될 수 있다.
        - 기존 필드의 매핑을 변경해야 하는 경우 새 데이터 스트림을 만들고 여기에 데이터를 다시 인덱싱하는 것이 권장된다.
        
        ### Change a dynamic index setting for a data stream
        
        1. 데이터 스트림에서 사용되는 인덱스 템플릿 업데이트. 이로 인해 스트림에 대해 생성된 향후 지원 인덱스에 설정이 추가된다. 
        2. 업데이트 인덱스 설정 API를 사용하여 데이터 스트림에 대한 인덱스 설정 업데이트. 이는 쓰기 인덱스를 포함하여 스트림의 기존 지원 인덱스에 설정을 적용한다. 
        
        ### Change a static index setting for a data stream
        
        - 정적 인덱스 설정은 지원 인덱스가 생성될 때만 설정할 수 있다. 색인 설정 업데이트 API를 사용하여 정적 색인 설정을 업데이트할 수 없다.
        - 향후 지원 인덱스에 새로운 정적 설정을 적용하려면 데이터 스트림에서 사용하는 인덱스 템플릿을 업데이트해야 한다. 이 설정은 업데이트 후에 생성된 모든 지원 인덱스에 자동으로 적용된다.
        - 기존 지원 인덱스에 정적 설정 변경 사항을 적용하려면 새 데이터 스트림을 생성하고 여기에 데이터를 다시 인덱싱해야 한다.
        
        ### Use reindex to change mappings or settings
        
        - 데이터 스트림을 다시 색인화하려면 먼저 원하는 매핑 또는 설정 변경 사항이 포함되도록 색인 템플릿을 생성하거나 업데이트해야 한다. 그 뒤 기존 데이터 스트림을 템플릿과 일치하는 새 스트림으로 다시 인덱싱할 수 있다.
        - 이렇게 하면 템플릿의 매핑 및 설정 변경 사항이 새 데이터 스트림에 추가된 각 문서 및 지원 인덱스에 적용된다. 이 변경 사항은 새 스트림에서 생성되는 향후 지원 인덱스에도 영향을 미친다.
        - 데이터 스트림의 재 색인화는 다음과 같이 수행한다.
            1. 새 데이터 스트림의 이름이나 인덱스 패턴을 선택한다. 새로운 데이터 스트림에는 기존 스트림의 데이터가 포함된다. 
                - Resolve index API를 사용하여 이름이나 패턴이 기존 인덱스, 별칭 또는 데이터 스트림과 일치하는지 확인할 수 있다.
            2. 색인 템플릿을 생성하거나 업데이트한다. 이 템플릿에는 새 데이터 스트림의 지원 색인에 적용하려는 매핑과 설정이 포함되어야 한다. 
                - 이 인덱스 템플릿은 데이터 스트림 템플릿에 대한 요구 사항을 충족해야 한다. 또한 index_patterns 속성에서 이전에 선택한 이름이나 인덱스 패턴도 포함되어야 한다.
            3. 데이터 스트림 생성 API를 사용하여 새 데이터 스트림을 수동으로 생성한다. 데이터 스트림의 이름은 새 템플릿의 index_patterns 속성에 정의된 인덱스 패턴과 일치해야 한다. 
            4. 새 데이터 스트림에 새 데이터와 이전 데이터를 혼합하지 않으려면 새 문서의 인덱싱을 일시 중지해야 한다. 이전 데이터와 새 데이터를 혼합하는 것은 안전하지만 데이터 보존을 방해할 수 있다. 
            5. ILM을 사용하여 롤오버를 자동화하는 경우 ILM 폴링 간격을 줄여야 한다. 기본적으로 ILM은 10분마다 롤오버 조건을 확인한다.
            6. create의 op_type을 사용하여 새 데이터 스트림으로 다시 색인화한다. 
                - 원래 인덱싱된 순서대로 데이터를 분할하려는 경우 별도의 재인덱싱 요청을 실행할 수 있다.
            7. 이전에 ILM 폴링 간격을 변경한 경우 재인덱싱이 완료되면 다시 원래 값으로 변경해야 한다. 이는 마스터 노드에 불필요한 로드를 방지한다. 
            8. 새 데이터 스트림을 사용하여 인덱싱을 재개한다. 
            9. 다시 색인화된 모든 데이터를 새 데이터 스트림에서 사용할 수 있음을 확인한 후에는 이전 스트림을 안전하게 제거할 수 있다.
    
    ### Update or add an alias to a data stream
    
    - 별칭 API를 사용하여 기존 데이터 스트림의 별칭을 업데이트한다. 인덱스 패턴에서 기존 데이터 스트림의 별칭을 변경하는 것은 아무런 효과가 없다.
- Time series data stream (TSDS)
    - Time series data stream(TSDS)
        
        ### Time series data stream(TSDS)
        
        - 시계열 데이터 스트림(TSDS)은 타임스탬프가 지정된 측정항목 데이터를 하나 이상의 시계열로 모델링한다.
        - TSDS를 사용하여 메트릭 데이터를 보다 효율적으로 저장할 수 있다.
            
            ### When to use a TSDS
            
            - 일반 데이터 스트림과 TSDS 모두 타임스탬프가 지정된 측정항목 데이터를 저장할 수 있다.
            - 일반적으로 거의 실시간으로 @timestamp 순서로 Elasticsearch에 메트릭 데이터를 추가하는 경우에만 TSDS를 사용하는 것을 권장한다.
            - TSDS는 측정항목 데이터에만 사용된다. 로그 또는 추적과 같이 타임스탬프가 지정된 다른 데이터의 경우 일반 데이터 스트림 사용이 권장된다.
            
            ### Differences from a regular data stream
            
            - TSDS는 다음의 차이점을 제외하고 일반 데이터 스트림처럼 작동한다.
                - TSDS에 대해 일치하는 인덱스 템플릿에는 index.mode: time_series 옵션이 있는 data_stream 객체가 필요하다.
                - @timestamp 외에도 TSDS의 각 문서에는 하나 이상의 차원 필드가 포함되어야 한다. TSDS에 대해 일치하는 인덱스 템플릿에는 하나 이상의 키워드 차원에 대한 매핑이 포함되어야 한다.
                - TSDS 문서에는 일반적으로 하나 이상의 측정항목 필드도 포함된다.
                - Elasticsearch는 TSDS의 각 문서에 대해 숨겨진 _tsid 메타데이터 필드를 생성한다.
                - TSDS는 시간 제한이 있는 백업 인덱스를 사용하여 동일한 백업 인덱스에 동일한 기간의 데이터를 저장한다.
                - TSDS에 대해 일치하는 인덱스 템플릿에는 index.routing_path 인덱스 설정이 포함되어야 한다. TSDS는 이 설정을 사용하여 차원 기반 라우팅을 수행한다.
                - TSDS는 내부 인덱스 정렬을 사용하여 _tsid 및  @timestamp를 기준으로 샤드 세그먼트를 정려한다. TSDS 문서는 자동 생성된 문서 _id 값만 지원한다.
                - TSDS는 합성 _source를 사용하므로 여러 가지 제한 사항이 적용된다.
            
            ### What is a time series?
            
            - 시계열은 특정 엔터티에 대한 일련의 관찰이다.
            - 이러한 관찰을 통해 시간 경과에 따른 엔터티의 변경 사항을 추적할 수 있다.
            - TSDS에서 각 Elasticsearch 문서는 특정 시계열의 관찰 또는 데이터 포인트를 나타낸다. TSDS에는 여러 시계열이 포함될 수 있지만 문서는 하나의 시계열에만 속할 수 있다.
                
                ### Dimensions
                
                - 차원은 문서의 시계열을 식별하는 필드 이름과 값이다. 대부분의 경우 차원은 측정 중인 엔터티의 일부 측면을 설명한다.
                - TSDS 문서는 문서_id를 생성하는 데 사용되는 시계열과 타임스탬프로 고유하게 식별된다. 즉 크기와 타임스탬프가 동일한 두 문서는 중복된 것으로 간주된다.
                
                ### Metrics
                
                - 측정항목(Metrics)은 숫자 측정값뿐만 아니라 해당 측정값을 기반으로 한 집계 및 다운샘플링 값도 포함하는 필드다. 필수는 아니지만 TSDS의 문서에는 일반적으로 하나 이상의 측정항목 필드가 포함된다.
                - 필드를 지표로 표시할려면 time_series_metric 매핑 매개변수를 사용하여 필드 유형을 지정해야 한다.
            
            ### Time series mode
            
            - TSDS에 대해 일치하는 인덱스 템플릿에는 index_mode:time_series 옵션이 있는 data_stream 개체가 포함되어야 한다.
                
                ### _tsid metadata field
                
                - TSDS에 문서를 추가하면 Elasticsearch는 _tsid 문서에 대한 메타데이터 필드를 자동으로 생성한다.
                - _tsid는 문서의 크기를 포함하는 개체로 동일한 _tsid를 가진 동일한 TSDS의 문서는 동일한 시계열으 ㅣ일부다.
                - _tsid 필드는 쿼리하거나 업데이트할 수 없다.
                
                ### Time-bound indices
                
                - TSDS에서 가장 최근의 지원 인덱스를 포함한 각 지원 인덱스에는 허용되는 @timestamp 값의 범위가 있다. 이 범위는 index.time_series.start_time 및 index.time_series.end_time 인덱스 설정에 의해 정의된다.
                
                ### Look-ahead time
                
                - index.look_ahead_time 인덱스 설정을 사용하여 인덱스에 문서를 추가할 수 있는 향후 기간을 구성한다.
                - TSDS에 대한 새 쓰기 인덱스를 생성하면 Elasticsearch는 인덱스의 index.time_series.end_time 값을 다음과 같이 계산한다
                    - now + index.look_ahead_time
                - 이 프로세스는 쓰기 인덱스가 롤오버될 때까지 계속된다.
                
                ### Accepted time range for adding data
                
                - TSDS는 현재 측정항목 데이터를 수집하도록 설계되었다. TSDS가 처음 생성될 때 초기 지원 인덱스는 다음과 같다.
                    - 현재 시간으로 설정된 index.time_series.start_time 값 - index.look_ahead_time
                    - 현재 시간으로 설정된 index.time_series.end_time + index.look_ahead_time
                - 위 범위에 속하는 데이터만 색인을 생성할 수 있다.
                
                ### Dimension-based routing
                
                - 각 TSDS 지원 인덱스 내에서 Elasticsearch는 index.routing_path 인덱스 설정을 사용하여 동일한 차원을 가진 문서를 동일한 샤드로 라우팅한다.
                - TSDS에 대해 일치하는 인덱스 템플릿을 생성할 때 index.routing_path 설정에서 하나 이상의 차원을 지정해야 한다.
                
                ### Index sorting
                
                - Elasticsearch는 압축 알고리즘을 사용하여 반복되는 값을 압축한다. 이 압축은 반복되는 값이 서로 가까이, 즉 동일한 인덱스, 동일한 샤드, 동일한 샤드 세그먼트에 나란히 저장될 때 가장 잘 작동한다.
    - Set up a TSDS
        
        ### Set up a time series data stream (TSDS)
        
        - TSDS를 설정하려면 다음 단계를 진행해야 한다.
            1. 전제조건 확인
            2. 인덱스 생명 주기 정책 생성
            3. 매핑 구성 요소 템플릿 생성
            4. 색인 설정 구성 요소 템플릿 생성
            5. 색인 템플릿 생성
            6. TSDS 생성
            7. TSDS 보호
            
            ### Prerequisites
            
            - TSDS 생성하기 전 데이터 스트림과 TSDS 개념에 대한 이해가 필요하다.
            - TSDS 생성에는 다음 권한이 필요하다.
                - 클러스터 권한 : manage_lim, manage_index_templates
                - 인덱스 권한 : 생성하거나 변환하는 모든 TSDS에 대한 create_doc, create_index 권한. TSDS를 롤오버하려면 manage 권한이 있어야 한다.
            
            ### Create an index lifecycle policy
            
            - 선택 사항이지만 ILM을 사용하여 TSDS의 지원 색인 관리를 자동화하는 것이 좋다. ILM에는 인덱스 생명 주기 정책이 필요하다.
            - 정책에서 롤오버 작업에 대한 max_age 기준을 지정하는 것이 좋다. 이렇게 하면 TSDS의 지원 색인에 대한 @timestamp 범위가 일관되게 유지된다.
                
                ```json
                PUT _ilm/policy/my-weather-sensor-lifecycle-policy
                {
                  "policy": {
                    "phases": {
                      "hot": {
                        "actions": {
                          "rollover": {
                            "max_age": "1d",
                            "max_primary_shard_size": "50gb"
                          }
                        }
                      },
                      "warm": {
                        "min_age": "30d",
                        "actions": {
                          "shrink": {
                            "number_of_shards": 1
                          },
                          "forcemerge": {
                            "max_num_segments": 1
                          }
                        }
                      },
                      "cold": {
                        "min_age": "60d",
                        "actions": {
                          "searchable_snapshot": {
                            "snapshot_repository": "found-snapshots"
                          }
                        }
                      },
                      "frozen": {
                        "min_age": "90d",
                        "actions": {
                          "searchable_snapshot": {
                            "snapshot_repository": "found-snapshots"
                          }
                        }
                      },
                      "delete": {
                        "min_age": "735d",
                        "actions": {
                          "delete": {}
                        }
                      }
                    }
                  }
                }
                ```
                
            
            ### Create a mappings component template
            
            - TSDS에는 일치하는 인덱스 템플릿이 필요하다. 대부분의 경우 하나 이상의 구성 요소 템플릿을 사용하여 인덱스 템플릿을 구성한다.
            - 일반적으로 매핑 및 인덱스 설정에는 별도의 구성 요소 템플릿을 사용한다.
            - 이를 통해 여러 인덱스 템플릿에서 구성 요소 템플릿을 재사용할 수 있다.
            - TSDS의 경우 매핑 구성 요소 템플릿에는 다음에 대한 매핑이 포함되어야 한다.
                - time_series_dimension 값이 true인 하나 이상의 차원 필드. 차원 중 하나 이상은 일반 키워드 필드여야 한다.
            - 선택적으로, 템플릿에는 다음에 대한 매핑도 포함될 수 있다.
                - time_series_metric 매핑 매개변수를 사용하여 표시된 하나 이상의 측정항목 필드
                - @timestamp 필드에 대한 date 또는 date_nanos 매핑
                    
                    ```json
                    PUT _component_template/my-weather-sensor-mappings
                    {
                      "template": {
                        "mappings": {
                          "properties": {
                            "sensor_id": {
                              "type": "keyword",
                              "time_series_dimension": true
                            },
                            "location": {
                              "type": "keyword",
                              "time_series_dimension": true
                            },
                            "temperature": {
                              "type": "half_float",
                              "time_series_metric": "gauge"
                            },
                            "humidity": {
                              "type": "half_float",
                              "time_series_metric": "gauge"
                            },
                            "@timestamp": {
                              "type": "date",
                              "format": "strict_date_optional_time"
                            }
                          }
                        }
                      },
                      "_meta": {
                        "description": "Mappings for weather sensor data"
                      }
                    }
                    ```
                    
            
            ### Create an index settings component template
            
            - 선택적으로 TSDS의 인덱스 설정 구성 요소 템플릿에는 다음이 포함될 수 있다.
                - index_lifecycle.name 인덱스 설정의 생명 주기 정책
                - index.look_ahead_time의 인덱스 설정
                - TSDS의 지원 인덱스에 대한 index.codec와 같은 기타 색인 설정
            
            ### Create an index template
            
            - 구성 요소 템플릿을 사용하여 인덱스 템플릿을 만든다. 인덱스 템플릿에서 다음을 지정한다.
                - TSDS 이름과 일치하는 하나 이상의 인덱스 패턴. 데이터 스트림 명명 체계를 사용하는 것이 권장된다.
                - 템플릿에 데이터 스트림 활성화
                - index.mode로 설정된 time_series 객체
                - 매핑 및 기타 인덱스 설정이 포함된 구성 요소 템플릿
                - 내장 템플릿과의 충돌을 피하려면 우선순위가 200보다 높아야 한다.
            
            ### Create the TSDS
            
            - 인덱싱 요청은 TSDS에 문서를 추가한다. TSDS 문서에는 다음이 포함되어야 한다.
                - _timestamp 필드
                - 하나 이상의 차원 필드. 지정된 경우 하나 이상의 차원은 index.routing_path 인덱스 설정과 일치하는 키워드 필드여야 한다.
            - TSDS를 자동으로 생성하려면 TSDS 이름을 대상으로 하는 색인 생성 요청을 제출해야 한다.
            
            ### Secure the TSDS
            
            - 인덱스 권한을 사용하여 TSDS에 대한 액세스를 제어한다. TSDS에 대한 권한을 부여하면 해당 지원 인덱스에 대해 동일한 권한이 부여된다.
            
            ### Convert an existing data stream to a TSDS
            
            - 기존 일반 데이터 스트림을 TSDS로 변환할 수 있다. 이 경우 다음을 수행할 수 있다.
                - 새 항목을 생성하는 대신 기존 인덱스 생명 주기 정책, 구성 요소 템플릿, 인덱스 템플릿 편집 가능
                - TSDS를 생성하는 대신 쓰기 인덱스를 수정으로 롤오버.
    - Time series index settings
        
        ### Time series index settins
        
        - index.mode
            - Static, String
            - 인덱스의 모드. 유효한 값은 time_series와 null(모드 없음)
            - 기본 값은 null
        - index.time_series.start_time
            - Static, String
            - @timestamp 인덱스에서 허용되는 가장 빠른 @timestamp 값(포함)
            - index.mode가 time_series인 인덱스만 이 매개변수를 지원한다.
        - index.time_series.end_time
            - Dynamic, String
            - 인덱스에서 허용하는 최신 @timestamp 값(제외)
            - index.mode가 time_series인 인덱스만 이 매개변수를 지원한다.
        - index.look_ahead_time
            - Static, time units
            - TSDS의 쓰기 인덱스에 대한 index.time_series.end_time을 계산하는 데 사용되는 간격
            - 기본 값은 2h이며, 1m부터 7d까지 허용된다.
            - index.mode가 time_series인 인덱스만 이 매개변수를 지원한다.
            - 이 설정은 time_series.poll_interval 클러스터 설정보다 작을 수 없다.
        - index.routing_path
            - Static, String 또는 String 배열
            - TSDS의 문서를 인덱스 샤드에 라우팅하는 데 사용되는 일반 키워드 필드
            - 와일드카드를 지원한다.
            - index.mode가 time_series인 인덱스만 이 매개변수를 지원한다.
            - 단, 데이터 스트림을 제외하고 기본 값은 구성 요소 및 인덱스 템플릿에 정의된 time_series_dimension 값이 true인 차원 필드 목록
        - index.mapping.dimension_fields.limit
            - Dynamic, Integer
            - 인덱스에 대한 시계열 차원의 최대 수
            - 기본 값은 21
    - Downsampling a time series data stream
        
        ### Downsampling a time series data stream
        
        - 다운샘플링은 시계열 데이터를 축소된 단위로 저장하여 그 공간을 줄이는 방법을 제공한다.
        - 측정항목 솔루션은 시간이 지남에 따라 증가하는 대량의 시계열 데이터를 수집한다. 데이터가 오래되면 시스템의 현재 상태와 관련성이 낮아진다. 다운 샘플링 프로세스는 고정된 시간 간격 내의 문서를 단일 요약 문서로 롤업한다.각 요약 문서에는 원본 데이터의 통계적 표현이 포함되어 있다.
        - 다운샘플링을 사용하면 데이터 해상도와 정밀도를 스토리지 크기와 교환할 수 있다. 이를 ILM 정책에 포함시켜 오래된 지표 데이터의 볼륨 및 관련 비용을 자동으로 관리할 수 있다.
            
            ### How it works
            
            - 시계열은 특정 엔터티에 대해 시간이 지남에 따라 수집된 일련의 관찰이다.
            - 관찰된 샘플은 시계열 차원이 일정하게 유지되고 시계열 측정항목이 시간에 따라 변하는 연속 함수로 표시될 수 있다.
                
                ![https://www.elastic.co/guide/en/elasticsearch/reference/current/images/data-streams/time-series-function.png](https://www.elastic.co/guide/en/elasticsearch/reference/current/images/data-streams/time-series-function.png)
                
            - Elasticsearch 인덱스에서는 지표 이름 및 변화하는 지표 값과 함께 불변의 시계열 차원을 포함하는 각 타임스탬프에 대해 단일 문서가 생성된다. 단일 타임스탬프의 경우 여러 시계열 차원과 측정항목이 저장될 수 있다.
            - 다운샘플링은 원래 시계열을 더 높은 샘플링 간격의 데이터 스트림과 해당 데이터의 통계 표현으로 대체하여 오래되고 자주 액세스하지 않는 데이터에 대해 작동한다.
            
            ### Running downsampling on time series data
            
            - 시계열 색인을 다운샘플링하려면 Downsample API를 사용하고 fixed_interval을 원하는 세부 수준으로 설정한다.
                
                ```json
                POST /my-time-series-index/_downsample/my-downsampled-time-series-index
                {
                    "fixed_interval": "1d"
                }
                ```
                
            - ILM의 일부로 시계열 데이터를 다운샘플링하려면 ILM 정책에 다운 샘플링 작업을 포함하고 fixed_interval을 원하는 세부 수준으로 설정한다.
                
                ```json
                PUT _ilm/policy/my_policy
                {
                  "policy": {
                    "phases": {
                      "warm": {
                        "actions": {
                          "downsample" : {
                            "fixed_interval": "1h"
                          }
                        }
                      }
                    }
                  }
                }
                ```
                
            
            ### Querying downsampled indices
            
            - _search 및 _async_search 엔드포인트를 사용하여 다운샘플링된 인덱스를 쿼리할 수 있다. 단일 요청으로 여러 원시 데이터와 다운샘플링된 인덱스를 쿼리할 수 있으며, 단일 요청에는 다양한 세부사항의 다운샘플링된 인덱스가 포함될 수 있다.
            - 시간 기반 히스토그램 집계의 결과는 균일한 버킷 크기이며 각 다운샘플링된 인덱스는 다운샘플링 시간 간격을 무시하고 데이터를 반환한다.
                
                ### Notes on downsample queries
                
                - Kibana와 Elastic 솔루션을 통해 쿼리를 실행하면 쿼리된 인덱스 중 일부가 다운샘플링된다는 알림 없이 정상적인 응답이 반환된다.
                - 날짜 히스토그램 집계의 경우 fixed_intervals 달력 인식 간격만 지원된다.
                - UTC 날짜-시간만 지원된다.
            
            ### Restrictions and limitations
            
            - 시계열 데이터 스트림의 인덱스만 지원된다.
            - 데이터는 시간 차원만을 기준으로 다운샘플링된다. 다른 모든 차원은 수정 없이 새 인덱스에 복사된다.
            - 데이터 스트림 내에서 다운샘플링된 인덱스가 원본 인덱스를 대체하고 원본 인덱스는 삭제된다.
            - 다운샘플링 프로세스가 성공하려면 소스 인덱스가 읽기 전용 모드에 있어야 한다.
            - 동일한 기간 동안의 데이터를 여러 번 다운샘플링이 지원된다. 다운샘플링 간격은 다운샘플링된 인덱스 간격의 배수여야 한다
            - 다운샘플링은 ILM 작업으로 제공된다.
            - 다운샘플링된 새로운 인덱스는 원본 인덱스의 데이터 계층에 생성되며 해당 설정을 상속한다.
            - 숫자 gauge 및 counter 메트릭 타입이 지원된다.
            - 다운샘플링 구성은 시계열 데이터 스트림 인덱스 매핑에서 추출된다.
    - Run downsampling with ILM
        
        ### Run downsampling with ILM
        
        - ILM을 사용하여 다운샘플링을 테스트하는 방법 안내
        - 샘플링된 지표 세트의 저장 크기를 줄이기 위해 ILM 정책의 일부로 다운샘플링이 어떻게 작동하는지 확인한다.
            
            ### Create an index lifecycle policy
            
            - 시계열 데이터에 대한 ILM 정책을 만든다. 필수는 아니지만 시계열 데이터 스트림 인덱스 관리를 자동화하려면 ILM 정책을 사용하는 것이 좋다.
            - 다운샘플링을 활성화하려면 다운샘플링 작업을 추가하고 fixed_interval을 원래 시계열 데이터를 집계하려는 다운샘플링 간격으로 설정한다.
            
            ### Create an index template
            
            - ILM정책을 생성하면 기본 데이터 스트림에 대한 인덱스 템플릿이 생성된다.
            - 단순화를 위해 시계열 매핑에서 모든 time_series_metric 매개변수는 gauge 유형으로 설정되지만 counter metric 유형도 사용할 수 있다.
            - time_series_metric 값은 다운샘플링 중에 사용되는 통계 표현의 종류를 결정한다.
            - 인덱스 탬플릿에는 정적 시계열 차원 세트가 포함되어 있다. 시계열 차원은 다운샘플링 프로세스에 의해 변경되지 않는다.
            
            ### Ingest time series data
            
            - 대량 API 요청을 사용하여 자동으로 TSDS를 생성한다.

# Ingest pipelines

- Ingest pipelines
    
    ### Ingest pipelines
    
    - 수집 파이프라인을 사용하면 인덱싱하기 전에 데이터에 대한 일반적인 변환을 수행할 수 있다. 예를 들어 파이프라인을 사용하여 필드를 제거하고, 텍스트에서 값을 추출하고, 데이터를 강화할 수 있다.
    - 파이프라인은 프로세서라고 하는 일련의 구성 가능한 작업으로 구성된다. 각 프로세서는 순차적으로 실행되어 들어오는 문서에 특정 변경 사항을 적용한다. 프로세서가 실행된 후 Elasticsearch는 변환된 문서를 데이터 스트림이나 인덱스에 추가한다.
        
        ![Untitled](Elastic_Guide(8.9)/Untitled%204.png)
        
    - Elasticsearch는 파이프라인을 클러스터 상태로 저장한다.
        
        ### Prerequisites
        
        - ingest 노드 역할이 있는 노드는 파이프라인 처리를 처리한다. 수집 파이프라인을 사용하려면 클러스터에 수집 역할을 가진 노드가 하나 이상 있어야 한다. 수집 로드가 많은 경우 전용 수집 노드를 생성하는 것이 좋다.
        - Elasticsearch 보안 기능이 활성화된 경우 수집 파이프라인을 관리하려면 prepare_pipeline 클러스터 권한이 있어야 한다. Kibana의 수집 파이프라인 기능을 사용하려면 Cluster:monitor/nodes/info 클러스터 권한도 필요하다.
        - 강화 프로세서를 포함한 파이프라인에는 추가 설정이 필요하다.
        
        ### Create and manage pipelines
        
        - 파이프라인을 생성하려면 키바나의 파이프라인 생성 > 새 파이프라인을 클릭하면 생성 가능하다.
        - CSV에서 새 파이프라인 옵션을 사용하면 CSV를 사용하여 사용자 지정 데이터를 ECS에 매핑하는 수집 파이프라인을 생성할 수 있다. 사용자 정의 데이터를 매핑하면 데이터 검색이 더 쉬워지고 다른 데이터 세트의 시각화를 재사용할 수 있다.
        - 수집 API를 사용하여 파이프라인을 생성 및 관리할 수 있다.
            
            ```json
            PUT _ingest/pipeline/my-pipeline
            {
              "description": "My optional pipeline description",
              "processors": [
                {
                  "set": {
                    "description": "My optional processor description",
                    "field": "my-long-field",
                    "value": 10
                  }
                },
                {
                  "set": {
                    "description": "Set 'my-boolean-field' to true",
                    "field": "my-boolean-field",
                    "value": true
                  }
                },
                {
                  "lowercase": {
                    "field": "my-keyword-field"
                  }
                }
              ]
            }
            ```
            
        
        ### Manage pipeline versions
        
        - 파이프라인을 생성하거나 업데이트할 때 선택적 version 정수를 지정할 수 있다. 이 버전 번호를 if_version 매개변수와 함께 사용하여 파이프라인을 조건부로 업데이트할 수 있다.
        - 매개변수가 지정된 경우 if_version 업데이트가 성공하면 파이프라인 버전이 증가한다.
            
            ```json
            PUT _ingest/pipeline/my-pipeline-id
            {
              "version": 1,
              "processors": [ ... ]
            }
            ```
            
        
        ### Test a pipeline
        
        - 시뮬레이션 파이프라인 API를 사용하여 파이프라인을 테스트할 수도 있다. 요청 경로에 구성된 파이프라인을 지정할 수 있다.
            
            ```json
            POST _ingest/pipeline/my-pipeline/_simulate
            {
              "docs": [
                {
                  "_source": {
                    "my-keyword-field": "FOO"
                  }
                },
                {
                  "_source": {
                    "my-keyword-field": "BAR"
                  }
                }
              ]
            }
            ```
            
        
        ### Add a pipeline to an indexing request
        
        - pipeline 쿼리 매개변수를 사용하여 개별 또는 대량 인덱싱 요청의 문서에 파이프라인을 적용할 수 있다.
        - 쿼리별 업데이트 또는 재색인 API와 함께 pipeline 매개변수를 사용할 수도 있다.
            
            ```json
            POST my-data-stream/_update_by_query?pipeline=my-pipeline
            
            POST _reindex
            {
              "source": {
                "index": "my-data-stream"
              },
              "dest": {
                "index": "my-new-data-stream",
                "op_type": "create",
                "pipeline": "my-pipeline"
              }
            }
            ```
            
        
        ### Set a default pipeline
        
        - index.default_pipeline 인덱스 설정을 사용하여 기본 파이프라인을 설정한다.
        - pipeline 매개변수가 지정되지 않은 경우 Elasticsearch는 이 파이프라인을 인덱싱 요청에 적용한다.
        
        ### Set a final pipeline
        
        - index.final_pipeline 인덱스 설정을 사용하여 최종 파이프라인을 설정한다. Elasticsearch는 둘 다 지정되지 않은 경우에도 요청 또는 기본 파이프라인 뒤에 이 파이프라인을 적용한다.
        
        ### Pipeline for Beats
        
        - Elastic Beat에 수집 파이프라인을 추가하려면 <BEAT_NAME>.yml의 output.elasticsearch 아래에 파이프라인 매개변수를 지정해야 한다.
            
            ```json
            output.elasticsearch:
              hosts: ["localhost:9200"]
              pipeline: my-pipeline
            ```
            
        
        ### Pipeline for Fleet and Elastic Agent
        
        - Fleet은 파이프라인 인덱스 설정이 포함된 인덱스 템플릿을 사용하여 기본 수집 파이프라인을 적용한다.
        - Elasticsearch는 스트림의 이름 지정 체계를 기반으로 이러한 템플릿을 Fleet 데이터 스트림과 일치시킨다.
        - 각 기본 통합 파이프라인은 존재하지 않고 버전이 지정되지 않은 @custom 수집 파이프라인을 호출한다. 변경하지 않으면 파이프라인 호출이 데이터에 영향을 주지 않는다.
        
        ### Access source fields in a processor
        
        - 프로세서는 수신 문서의 소스 필드에 대한 읽기 및 쓰기 액세스 권한을 가진다. 프로세서의 필드 키에 액세스하려면 해당 필드 이름을 사용해야 한다.
            
            ```json
            PUT _ingest/pipeline/my-pipeline
            {
              "processors": [
                {
                  "set": {
                    "field": "_source.my-long-field",
                    "value": 10
                  }
                }
              ]
            }
            ```
            
        
        ### Access metadata fields in a processor
        
        - 프로세서는 이름으로 다음 메타데이터 필드에 액세스할 수 있다.
            - _index
            - _id
            - _routing
            - _dynamic_templates
        
        ```json
        PUT _ingest/pipeline/my-pipeline
        {
          "processors": [
            {
              "set": {
                "description": "Use geo_point dynamic template for address field",
                "field": "_dynamic_templates",
                "value": {
                  "address": "geo_point"
                }
              }
            }
          ]
        }
        ```
        
        ### Access ingest metadata in a processor
        
        - 수집 프로세서는 _ingest 키를 사용하여 수집 메타데이터를 추가하고 액세스할 수 있다.
        - 소스 및 메타데이터 필드와 달리 Elasticsearch는 기본적으로 수집 메타데이터 필드를 인덱싱하지 않는다.
        - 파이프라인은 기본적으로 _inest.timestamp 수집 메타데이터 필드만 생성한다. 이 필드에는 Elasticsearch가 문서의 색인 생성 요청을 수신한 시점의 타임스탬프가 포함되어 있다.
        
        ### Handling pipeline failures
        
        - 파이프라인의 프로세서는 순차적으로 실행된다. 기본적으로 파이프라인 처리는 이러한 프로세서 중 하나가 실패하거나 오류가 발생하면 중지된다.
        - 프로세서 오류를 무시하고 파이프라인의 나머지 프로세서를 실행하려면 ignore_failure를 true로 설정한다.
            
            ```json
            PUT _ingest/pipeline/my-pipeline
            {
              "processors": [
                {
                  "rename": {
                    "description": "Rename 'provider' to 'cloud.provider'",
                    "field": "provider",
                    "target_field": "cloud.provider",
                    "ignore_failure": true
                  }
                }
              ]
            }
            ```
            
        
        ### Conditionally run a processor
        
        - 각 프로세서는 Painless 스크립트로 작성된 선택적 if 조건문을 지원한다.
        - 제공된 경우 프로세서는 if 조건이 true인 경우에만 실행된다.
            
            ```json
            PUT _ingest/pipeline/my-pipeline
            {
              "processors": [
                {
                  "drop": {
                    "description": "Drop documents with 'network.name' of 'Guest'",
                    "if": "ctx?.network?.name == 'Guest'"
                  }
                }
              ]
            }
            ```
            
        - script.painless.reges.enables 클러스터 설정이 활성화된 경우 if 조건 스크립트에서 정규식을 사용할 수 있다.
        
        ### Conditionally apply pipelines
        
        - if 조건을 프로세서와 결합하여 pipeline 기준에 따라 문서에 다른 파이프라인을 적용한다.
        - 여러 데이터 스트림 또는 인덱스를 구성하는 데 사용되는 인덱스 템플릿에서 이 파이프라인을 기본 파이프라인으로 사용할 수 있다.
            
            ```json
            PUT _ingest/pipeline/one-pipeline-to-rule-them-all
            {
              "processors": [
                {
                  "pipeline": {
                    "description": "If 'service.name' is 'apache_httpd', use 'httpd_pipeline'",
                    "if": "ctx.service?.name == 'apache_httpd'",
                    "name": "httpd_pipeline"
                  }
                },
                {
                  "pipeline": {
                    "description": "If 'service.name' is 'syslog', use 'syslog_pipeline'",
                    "if": "ctx.service?.name == 'syslog'",
                    "name": "syslog_pipeline"
                  }
                },
                {
                  "fail": {
                    "description": "If 'service.name' is not 'apache_httpd' or 'syslog', return a failure message",
                    "if": "ctx.service?.name != 'apache_httpd' && ctx.service?.name != 'syslog'",
                    "message": "This pipeline requires service.name to be either `syslog` or `apache_httpd`"
                  }
                }
              ]
            }
            ```
            
        
        ### Get pipeline usage statistics
        
        - 노드 통계 API를 사용하여 전역 및 파이프라인별 수집 통계를 가져올 수 있다.
            
            ```json
            GET _nodes/stats/ingest?filter_path=nodes.*.ingest
            ```
            
- Enrich your data
    - Enrich your data
        
        ### Enrich your data
        
        - 강화 프로세서(Enrich processor)를 사용하여 수집 중에 기존 인덱스의 데이터를 수신 문서에 추가할 수 있다.
        - 강화 프로세서를 사용하여 수행할 수 있는 작업은 다음과 같다.
            - 알려진 IP 주소를 기반으로 웹 서비스 또는 공급업체 식별
            - 제품 ID를 기반으로 소매 주문에 제품 정보 추가
            - 이메일 주소를 기반으로 연락처 정보 추가
            - 사용자 좌표를 기준으로 우편번호 추가
            
            ### How the enrich processor works
            
            - 대부분의 프로세서는 독립적이며 들어오는 문서의 기존 데이터만 변경한다.
            - 강화 프로세서는 수신 문서에 새 데이터를 추가하며 몇 가지 특수 구성 요소가 필요하다.
                
                ![Untitled](Elastic_Guide(8.9)/Untitled%205.png)
                
                - 강화 정책
                    - 올바른 수신 문서에 올바른 강화 데이터를 추가하는 데 사용되는 일련의 구성 옵션
                    - 강화 정책에는 다음이 포함된다.
                        - 풍부한 데이터를 문서로 저장하는 하나 이상의 소스 인덱스 목록
                        - 프로세서가 강화 데이터를 수신 문서와 일치시키는 방법을 결정하는 정책 유형
                        - 들어오는 문서를 일치시키는 데 사용되는 소스 인덱스의 일치 필드
                        - 수신 문서에 추가하려는 소스 인덱스의 강화 데이터가 포함된 필드 강화
                - 소스 인덱스
                    - 들어오는 문서에 추가하려는 풍부한 데이터를 저장하는 인덱스. 일반 Elasticsearch 인덱스처럼 이러한 인덱스를 생성하고 관리할 수 있다.
                    - 강화 정책에서 여러 소스 인덱스를 사용할 수 있다. 여러 강화 정책에서 동일한 소스 인덱스를 사용할 수도 있다.
                - 강화 지수
                    - 특정 강화 정책과 연결된 특수 시스템 인덱스
                    - 수신 문서를 소스 인덱스의 문서와 직접 일치시키는 작업은 느리고 리소스 집약적일 수 있다. 작업 속도를 높이기 위해 강화 프로세서는 강화 인덱스를 사용한다.
                    - 강화 인덱스에는 소스 인덱스의 강화 데이터가 포함되어 있지만 이를 간소화하는 데 도움이 되는 몇 가지 특수 속성이 있다.
    - Set up an enrich processor
        
        
- Processor reference
    - Processor reference
        
        ### Ingest processor reference
        
        - Elasticsearch에는 구성 가능한 여러 프로세서가 포함되어 있다.
            
            ```json
            GET _nodes/ingest?filter_path=nodes.*.ingest.processors
            ```
            
            ### Processor plugins
            
            - 추가 프로세서를 플러그인으로 설치할 수 있다.
            - 클러스터의 모든 노드에 플러그인 프로세서를 설치해야 한다. 그렇지 않으면 Elasticsearch가 프로세서를 포함하는 파이프라인을 생성하지 못한다.
            - elasticsearch.yml에서 plugin.mandatory를 설정하여 플러그인을 필수로 표시한다.
            - 필수 플러그인이 설치되지 않으면 노드가 시작되지 않는다.
                
                ```json
                plugin.mandatory: my-ingest-plugin
                ```
                
    - Append
        
        ### Append processor
        
        - 필드가 이미 있고 배열인 경우 기존 배열에 하나 이상의 값을 추가한다. 스칼라를 배열로 변환하고 필드가 존재하고 스칼라인 경우 배열에 하나 이상의 값을 추가한다.
        - 필드가 존재하지 않는 경우 제공된 값을 포함하는 배열을 만든다.
        - 단일 값 또는 값 배열을 허용한다.
            
            ```json
            {
              "append": {
                "field": "tags",
                "value": ["production", "{{{app}}}", "{{{owner}}}"]
              }
            }
            ```
            
        - 추가 옵션
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 추가할 필드. 템플릿 조각을 지원한다. |
            | value | yes | - | 추가할 값. 템플릿 조각을 지원한다. |
            | allow_duplicates | no | true | flase이면 프로세서는 필드에 이미 있는 값을 추가하지 않는다.  |
            | media_type | no | application/json | 인코딩 값의 미디어 유형. 값이 템플릿 조각인 경우에만 적용된다. 
            application/json, text/plain, application/x-www-form-urlencoded 중 하나여야 한다. |
            | description | no | - | 프로세서에 대한 설명. 프로세서의 목적이나 구성을 설명하는 데 유용하다 |
            | if | no | - | 조건부로 프로세서를 실행한다. |
            | ignore_failure | no | false | 프로세서 오류를 무시한다.  |
            | on_failure | no | - | 프로세서 오류를 처리한다. |
            | tag | no | - | 프로세서의 식별자.  |
    - Attachment
        
        ### Bytes processor
        
        - 첨부 파일 프로세서를 사용하면 Elasticsearch에서 Apache 텍스트 추출 라이브러리 Tika를 사용하여 일반적인 형식의 첨부 파일을 추출할 수 있다.
        - 소스 필드는 base64로 인코딩된 바이너리여야 한다. base64간 변환에 따른 오버헤드를 발생시키지 않으려면 JSON 대신 CBOR 형식을 사용하고 필드를 문자열 대신 바이트 배열로 지정할 수 있다. 그럴 경우 프로세서는 base64 디코딩을 건너뛴다.
            
            ### Using the attachement processor in a pipeline
            
            - 추가 옵션
                
                
                | Name | Required | Default | Description |
                | --- | --- | --- | --- |
                | field | yes | - | base64로 인코딩된 필드를 가져오는 필드 |
                | target_field | no | attachment | 첨부 파일 정보를 보유할 필드 |
                | indexed_chars | no | 100000 | 거대한 필드를 방지하기 위해 추출에 사용되는 문자 수 |
                | indexed_chars_field | no | null | 추출에 사용되는 문자 수를 덮어쓸 수 있는 필드 이름 |
                | properties | no | all properties | 저장하기 위해 선택할 속성 배열 |
                | ignore_missing | no | false | true일 경우 field 프로세서는 문서를 수정하지 않고 종료된다.  |
                | remove_binary | no | false | true일 경우 바이너리가 field 문서에서 제거된다.   |
                | resource_name | no |  | 디코딩할 리소스의 이름이 포함된 필드. 지정된 경우 프로세서는 이 리소스 이름을 기본 Tika 라이브러리에 전달하여 리소스 이름 기반 감지를 활성화한다. |
            
            ### **Example**
            
            ### Use the attachment processor with CBOR
            
            - JSON을 base64로 인코딩 및 디코딩하지 않으려면 CBOR 데이터를 첨부 프로세서에 전달할 수 있다.
                
                ```json
                PUT _ingest/pipeline/cbor-attachment
                {
                  "description" : "Extract attachment information",
                  "processors" : [
                    {
                      "attachment" : {
                        "field" : "data",
                        "remove_binary": false
                      }
                    }
                  ]
                }
                ```
                
            
            ### Limit the number of extracted chars
            
            - 너무 많은 문자 추출을 방지하고 노드 메모리의 과부하를 방지하기 위해 추출에 사용되는 문자 수는 기본적으로 10000으로 제한된다.
            - indexed_chars를 설정하여 이 값을 변경할 수 없다.
            - 제한 없이 사용하되 설정 시 노드가 매우 큰 문서의 콘텐츠를 추출하기에 충분한 HEAP을 가지고 있는지 확인해야 한다
            - 특정 필드에서 설정할 제한을 추출하여 문서당 제한을 정의할 수도 있다.
            - 문서에 해당 필드가 있으면 indexed_chars 설정을 덮어쓴다.
            
            ### Using the attachement processor with arrays
            
            - 첨부 파일 배열 내에서 첨부 프로세서를 사용하려면 foreach 프로세서가 필요하다. 이를 통해 연결 프로세서가 배열의 개별 요소에서 실행될 수 있다.
    - Bytes
        
        ### Bytes processor
        
        - 사람이 읽을 수 있는 바이트 값(1kb)을 바이트값(1024)로 변환한다.
        - 필드가 문자열 배열인 경우 배열의 모든 구성원이 반환된다.
        - 사람이 읽을 수 있는 단위는 대소문자를 구분하지 않으며 필드가 지원되는 형식이 아니거나 결과값이 2^63을 초과하면 오류가 발생한다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 반환할 필드 |
            | target_field | no | field | 반환된 값을 할당할 필드는 기본적으로 field 내부에서 업데이트 된다.  |
            | ignore_missing | no | false | true이고 필드가 존재하지 않거나 null인 경우 프로세서는 문서를 수정하지 않고 종료된다.  |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부로 프로세서를 실행한다. |
            | ignore_failure | no | false | 프로세서 오류를 무시한다.  |
            | on_failure | no | - | 프로세서 오류를 처리한다. |
            | tag | no | - | 프로세서의 식별자 |
    - Circle
        
        ### Circle processor
        
        - 모양의 원에 대한 정의를 대략적인 정다각형으로 반환한다.
        - Circle processor 옵션
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 원으로 해석될 필드. WKT 형식의 문자열 또는 GeoJSON 지도 |
            | target_field | no | field | 기존적으로 다각형 모양을 할당할 필드는 field 내부에서 업데이트 된다. |
            | ignore_missing | no | false | true가 아닐 경우 field 프로세서는 문서를 수정하지 않고 종료된다. |
            | error_distance | yes | - | 중심에서 측면까지 결과 내접 거리와 원의 반경 사이의 차이  |
            | shape_type | yes | - | 원을 처리할 때 사용할 필드 매핑 유형 |
            | description | no | - | 프로세서에 대한 설명  |
            | if | no | - | 조건부로 프로세서를 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서의 식별자 |
            
            ```json
            PUT circles
            {
              "mappings": {
                "properties": {
                  "circle": {
                    "type": "geo_shape"
                  }
                }
              }
            }
            
            PUT _ingest/pipeline/polygonize_circles
            {
              "description": "translate circle to polygon",
              "processors": [
                {
                  "circle": {
                    "field": "circle",
                    "error_distance": 28.0,
                    "shape_type": "geo_shape"
                  }
                }
              ]
            }
            ```
            
        
        ### Notes on Accuracy
        
        - 원을 나타내는 다각형의 정확도는 error_distance로 정의된다. 이 차이가 작을수록 다각형은 완벽한 원에 가까워진다.
    - Community ID
        
        ### Community ID Processor
        
        - 커뮤니티 ID 사양에 정의된 대로 네트워크 흐름 데이터에 대한 커뮤니티 ID를 계산한다. 커뮤니티 ID를 사용하여 단일 흐름과 관련된 네트워크 이벤트를 상호 연관시킬 수 있다.
        - 커뮤니티 ID 프로세서는 기본적으로 관련 ECS(Elastic Common Schema) 필드에서 네트워크 흐름 데이터를 읽는다. ECS를 사용하는 경우에는 구성이 필요하지 않다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | source_ip | no | source.ip | 소스 IP 주소가 포함된 필드 |
            | source_port | no | source.port | 소스 포트가 포함된 필드 |
            | destination_ip | no | destination.ip | 대상 IP 주소가 포함된 필드 |
            | destination_port | no | destination.port | 대상 포트가 포함된 필드 |
            | iana_number | no | network.iana_number | IANA 번호가 포함된 필드 |
            | icmp_type | no | icmp.type | ICMP 유형이 포함된 필드 |
            | icmp_code | no | icmp.code | ICMP 코드가 포함된 필드 |
            | transport | no | network.transport | 전송 프로토콜이 포함된 필드
            iana_number 필드가 없을 경우에만 사용 가능 |
            | target_field | no | network.community_id | 커뮤니티 ID의 출력 필드 |
            | seed | no | 0 | 커뮤니티 ID 해시의 시드. 
            0 ~ 65535(포함) 사이여야 한다.  |
            | ignore_missing | no | true | true일 때 필수 필드가 누락된 경우 프로세서는 문서를 수정하지 않고 자동으로 종료된다. |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부로 프로세서를 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - Convert
        
        ### Convert processor
        
        - 문자열을 정수로 변환하는 것과 같이 현재 수집된 문서의 필드를 다른 유형으로 변환한다. 필드 값이 배열이면 모든 멤버가 변환된다.
        - 지원되는 유형은 integer, long, float, double, string, boolean, ip, auto
            - boolean을 지정하면 문자열 값이 true(대소문자 무시)이면 필드가 true로 설정되고, 문자열 값이 false면 false로 설정되며 그렇지 않을 경우 예외가 발생한다.
            - ip를 지정하면 IP 필드 유형으로 인덱싱할 수 있는 유효한 IPv4 또는 IPv6 주소가 포함된 경우 대상 필드가 필드 값으로 설정된다.
            - auto 값을 지정하면 문자열 값 필드를 가장 가까운 비문자열, 비 IP 유형으로 변환하려고 시도한다.
        
        | Name | Required | Default | Description |
        | --- | --- | --- | --- |
        | field | yes | - | 값을 변환할 필드 |
        | target_field | no | field | 변환된 값을 할당할 필드는 기본적으로 field 내부에서 업데이트 된다. |
        | type | yes | - | 기존 값을 변환할 유형 |
        | ignore_missing | no | false | field가 존재하지 않거나, null이 존재하지 않거나 true인 경우 프로세서는 문서를 수정하지 않고 종료된다. |
        | description | no | - | 프로세서에 대한 설명 |
        | if | no | - | 조건부로 프로세서 실행 |
        | ignore_failure | no | false | 프로세서 오류 무시 |
        | on_failure | no | - | 프로세서 오류 처리 |
        | tag | no | - | 프로세서 식별자 |
    - CSV
        
        ### CSV processor
        
        - 문서 내의 단일 텍스트 필드에서 CSV 라인의 필드를 추출한다.
        - CSV의 빈 필드는 건너 뛴다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 데이터를 추출할 필드 |
            | target_fields | yes | - | 추출된 값을 할당할 필드 배열 |
            | separator | no | , | CSV에서 사용되는 구분 기호는 단일 문자열이어야 한다. |
            | quote | no | " | CSV에서 사용되는 인용문은 단일 문자열이어야 한다 |
            | ignore_missing | no | false | true이거나 field 가 존재하지 않을 경우 프로세서는 문서를 수정하지 않고 종료한다. |
            | trim | no | false | 따움표가 없는 필드의 공백 자르기 |
            | empty_value | no | - | 빈 필드를 채우는 데 사용되는 값 |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류 무시 |
            | on_failure | no | - | 프로세서 오류 처리  |
            | tag | no | - | 프로세서 식별자 |
    - Date
        
        ### Date processor
        
        - 필드의 날짜를 구문 분석한 다음 날짜 또는 타임스탬프를 문서의 타임스탬프로 사용한다.
        - 기본적으로 날짜 처리기는 구문 분석된 날짜를 @timestamp 라는 새 필드로 추가한다.
        - target_field 구성 매개변수를 설정하여 다른 필드를 지정할 수 있다.
        - 동일한 날짜 프로세서 정의의 일부로 여러 날짜 형식이 지원된다.
        - 프로세서 정의의 일부로 정의된 것과 동일한 순서로 날짜 필드 구문 분석을 시도하기 위해 순차적으로 사용된다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 날짜를 가져올 필드 |
            | target_field | no | @timestamp | 구문 분석된 날짜를 보유할 필드 |
            | formats | yes | - | 예상 날짜 형식의 배열  |
            | timezone | no | UTC | 날짜를 구문 분석할 때 사용할 시간대 |
            | locale | no | ENGLISH | 날짜를 구문 분석할 때 사용할 로케일  |
            | output_format | no | yyyy-MM-dd'T'HH:mm:ss.SSSXXX | target_field에 날짜를 쓸 때 사용할 형식 
            유효한 Java 시간 패턴이어야 한다 |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부로 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류 무시 |
            | on_failure | no | - | 프로세서 처리 |
            | tag | no | - | 프로세서 식별자 |
    - Date index name
        
        ### Date index name processor
        
        - 프로세서의 목적은 date math index name support을 사용하여 문서의 날짜 또는 타임스탬프 필드를 기반으로 올바른 시간 기반 인덱스를 문서에 지정하는 것이다.
        - 프로세서는 제공된 인덱스 이름 접두사, 처리 중인 문서의 날짜 또는 타임스탬프 필드 및 제공된 날짜 반올림을 기반으로 날짜 수학 인덱스 이름 표현식을 사용하여 _index 메타데이터 필드를 설정한다
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 날짜 또는 타임스탬프를 가져올 필드 |
            | index_name_prefix | no | - | 인쇄될 날짜 앞에 추가될 인덱스 이름의 접두사  |
            | date_rounding | yes | - | 날짜를 인덱스 이름으로 형식화할 때 날짜를 반올림하는 방법 |
            | date_formats | no | yyyy-MM-dd'T'HH:mm:ss.SSSXX | 전처리 중인 문서의 날짜/타임스탬프를 구문 분석하기 위한 예상 날짜 형식의 배열 |
            | timezone | no | UTC | 날짜를 구문 분석할 때 사용하는 시간대와 날짜 수학 인덱스가 지원하는 시기는 표현식을 구체적인 인덱스 이름으로 확인한다 |
            | locale | no | ENGLISH | 전처리 중인 문서에서 날짜를 구문 분석할 때 사용할 로케일 |
            | index_name_format | no | yyyy-MM-dd | 구문 분석된 날짜를 인덱스 이름에 인쇄할 때 사용되는 형식 |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류 무시 |
            | on_failure | no | - | 프로세서 오류 처리 |
            | tag | no | - | 프로세서 식별자 |
    - Dissect
        
        ### Dissect processor
        
        - Grok 프로세서와 유사하게, Dissect는 문서 내의 단일 텍스트 필드에서 구조화된 필드도 추출한다. 그러나 Grok 프로세서와 달리 dissect는 정규식은 사용하지 않는다. 이를 통해 dissect의 구문을 간단하게 하고 어떤 경우에는 Grok 프로세서보다 더 빠르게 만들 수 있다.
        - Dissect는 단일 텍스트 필드를 정의된 패턴과 일치시킨다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | dissect할 분야 |
            | pattern | yes | - | 적용할 패턴 |
            | append_separator | no | "" (empty string) | 추가된 필드를 구분하는 문자 |
            | ignore_missing | no | false | true이면서 field가 존재하지 않거나 null일 경우,  프로세서는 문서를 수정하지 않고 종료된다 |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서 식별자 |
        
        ### Dissect key modifiers
        
        - 키 수정자는 dissect의 기본 동작을 변경할 수 있다. 키 수정자는 항상 %{keyname} 내부의 왼쪽이나 오른쪽에서 찾을 수 있다.
        
        | Modifier | Name | Position | Example | Description |
        | --- | --- | --- | --- | --- |
        | -> | Skip right padding | (far) right | %{keyname1->} | 반복되는 문자를 오른쪽으로 건너뛴다. |
        | + | Append | left | %{+keyname} %{+keyname} | 두 개 이상의 필드를 함께 추가한다 |
        | + with /n | Append with order | left and right | %{+keyname/2} %{+keyname/1} | 지정된 순서대로 두 개 이상의 필드를 함께 추가한다 |
        | ? | Named skip key | left | %{?ignoreme} | 출력에서 일치하는 값을 건너뛴다 |
        | * and & | Reference keys | left | %{*r1} %{&r1} | 출력 키를 *으로 설정하고 &을 출력 값으로 설정한다  |
    - Dot expander
        
        ### Dot expander processor
        
        - 점이 있는 필드를 개체 필드로 확장한다. 프로세서를 사용할 경우 이름에 점이 있는 필드에 파이프라인의 다른 프로세서가 액세스할 수 있다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 개체 필드로 확장할 필드. *로 설정하면 모든 최상위 필드가 확장된다. |
            | path | no | - | 확장할 필드가 포함된 필드 
            field 옵션은 리프 필드만 이해할 수 있으므로 확장할 필드가 다른 개체 필드의 일부인 경우에만 필요하다 |
            | override | no | false | 확장된 필드와 충돌하는 기존 중첩 개체가 이미 있는 경우 동작을 제어한다. flase이면 프로세서는 이전 값과 새 값을 배열로 결합하여 충돌을 병합한다 
            true인 경우 확장된 필드의 값이 기존 값을 덮어쓴다 |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부로 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류 무시 |
            | on_failure | no | - | 프로세서 오류 처리 |
            | tag | no | - | 프로세어 식별자 |
    - Drop
        
        ### Drop processor
        
        - 오류를 발생시키지 않고 문서를 삭제한다. 특정 조건에 따라 문서가 색인화되는 것을 방지하는 데 유용하다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류 무시 |
            | on_failure | no | - | 프로세서 오류 처리 |
            | tag | no | - | 프로세서 식별자 |
    - Enrich
        
        ### Enrich processor
        
        - enrich 프로세서는 다른 인덱스의 데이터로 문서를 보강할 수 있다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | policy_name | yes | - | 사용할 강화 정책의 이름 |
            | field | yes | - | 강화 데이터를 검색하는 데 사용되는 match_field 정책과 일치하는 입력 문서의 필드 |
            | target_field | yes | - | 풍부한 데이터를 포함하기 위해 수신 문서에 필드가 추가되었다. 
            필드에는 강화 정책에 지정된 match_field 및 enrich_fields가 모두 포함되어 있다 |
            | ignore_missing | no | false | true가 아닐 경우 field 프로세서는 문서를 수정하지 않고 종료된다. |
            | override | no | true | 프로세서가 기존의 null 값이 아닌 필드로 필드를 업데이트 하는 경우 flase로 설정하면 해당 필드는 건드리지 않는다 |
            | max_matches | no | 1 | 구성된 대상 필드에 포함할 일치 문서의 최대 수 
            max_matches가 1보다 높으면 target_field는 json 배열로 변환되고, 그렇지 않으면 target_field는 json 객체가 된다. 
            최대 값은 128 |
            | shape_relation | no | INTERSECTS | 수신 문서의 지리 형태를 강화 인덱스의 문서와 일치시키는 데 사용되는 공간 관계 연산자 
            geo_match 강화 정책 유형에만 사용되는 옵션 |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류 무시 |
            | on_failure | no | - | 프로세서 오류 처리 |
            | tag | no | - | 프로세서 식별자 |
    - Fail
        
        ### Fail Processor
        
        - 예외를 발생시킨다. 파이프라인이 실패할 것으로 예상하고 특정 메시지를 요청자에게 전달하려는 경우에 유용하다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | message | yes | - | 프로세서에서 발생하는 오류 메시지 |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부로 프로세서를 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - Fingerprint
        
        ### Fingerprint processor
        
        - 문서 콘텐츠의 해시를 계산한다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | fields | yes | n/a | Fingerprint에 포함할 필드 배열 
            객체의 경우 프로세서는 필드 키와 값을 모두 해시한다. 다른 필드의 경우 프로세서는 필드 값만 해시한다 |
            | target_field | no | fingerprint | Fingerprint의 출력 필드 |
            | salt | no | <none> | 해시 함수의 솔트 값 |
            | method | no | SHA-1 | Fingerprint를 계산하는 데 사용되는 해시 방법 
            MD5, SHA-1, SHA-256, SHA-512, MurmurHash3 중 하나여야 한다. |
            | ignore_missing | no | false | ture일 경우 프로세서는 누락된 필드 항목을 무시한다 
            모든 필드가 누락된 경우 프로세서는 문서를 수정하지 않고 자동으로 종료된다.  |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부로 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서 식별자 |
    - Foreach
        
        ### Foreach processor
        
        - 배열 또는 객체의 각 요소에 대해 수집 프로세서를 실행한다
        - Foreach 프로세서를 사용하면 배열 또는 객체 값이 포함된 필드와 필드의 각 요소에서 실행할 프로세서를 지정할 수 있다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 배열 또는 객체 값을 포함하는 필드 |
            | processor | yes | - | 각 요소에서 실행할 프로세서를 수집 |
            | ignore_missing | no | false | true이면 문서가 누락되거나 field가 null인 경우 문서를 변경하지 않고 프로세서가 자동으로 종료된다. |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류 무시 |
            | on_failure | no | - | 프로세서 오류 처리 |
            | tag | no | - | 프로세서의 식별자 |
            
            ### Access keys and values
            
            - 배열이나 객체를 반복할 때 foreach 프로세서는 현재 요소의 값을 _ingest._value 수집 메타데이터 필드에 저장한다. _ingest._value에는 하위 필드를 포함한 전체 요소 값이 포함된다.
            - _ingest._value 필드에서 점 표기법을 사용하여 하위 필드 값에 액세스할 수 있다.
            - 객체를 반복할 때 foreach 프로세서는 현재 요소의 키를 _ingest._key에 문자열로 저장한다.
            
            ### Faillure handling
            
            - foreach 프로세서가 요소 처리에 실패하고 on_failure 프로세서가 지정되지 않은 경우 foreach 프로세서는 자동으로 종료된다. 이 경우 전체 배열이나 객체 값이 변경되지 않는다.
    - Geo-grid
        
        ### Geo-grid processor
        
        - 그리드 타일 또는 셀의 지리 그리드 정의를 해당 모양을 설명하는 일반 경계 상자 또는 다각형으로 변환한다. 이는 공간적으로 인덱싱 가능한 필드로서 타일 모양과 상호 작용해야 하는 경우에 유용하다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 지리 타일로 해석할 필드. 필드 형식은 tile_type에 의해 결정된다.  |
            | tile_type | yes | - | geohash, geotile, geohex 의 세 가지 타일 형식이 가능 |
            | target_field | no | field | 기본적으로 다각형 모양을 할당할 필드는 field 내부에서 업데이트된다.  |
            | parent_field | no | - | 지정하고 상위 타일이 존재하는 경우 해당 타일 주소를 이 필드에 저장한다 |
            | children_field | no | - | 지정되고 하위 타일이 존재하는 경우 해당 주소를 문자열 배열로 이 필드에 저장한다 |
            | non_children_field | no | - | 지정되고 교차하는 비자식 타일이 존재하는 경우 해당 주소를 문자열 배열로 이 필드에 저장 |
            | precision_field | no | - | 지정된 경우 타일 정밀도를 이 필드에 정수로 저장한다 |
            | ignore_missing | no | - | true거나 field가 존재하지 않으면 프로세서는 문서를 수정하지 않고 종료된다  |
            | target_format | no | "GeoJSON" | 생성된 다각형을 저장할 형식
            WKT 또는 GeoJSON |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부로 프로세서를 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - GeoIP
        
        ### GeoIP processor
        
        - geoip 프로세서는 IPv4 또는 IPv6 주소의 지리적 위치에 대한 정보를 추가한다.
        - 프로세서는 GeoLite2 City, GeoLite2 Country, GeoLite2 ASN GeoIP2 데이터베이스를 사용한다.
        - Elasticsearch는 Elastic GeoIP 엔드포인트에서 데이터베이스에 대한 업데이트를 자동으로 다운로드한다. 업데이트에 대한 다운로드 통계를 얻으려면 GeoIP 통계 API를 사용한다.
        - Elasticsearch가 30일 동안 엔드포인트에 연결할 수 없으면 업데이트된 모든 데이터베이스가 무효화된다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 지리적 조회를 위해 IP 주소를 가져오는 필드 |
            | target_field | no | geoip | MaxMind 데이터베이스에서 조회된 지리 정보를 보유할 필드 |
            | database_file | no | GeoLite2-City.mmdb | 모듈과 함께 제공되는 데이터베이스 또는 ingest_geoip 구성 디렉터리의 사용자 정의 데이터베이스를 참조하는 데이터베이스 파일 이름 |
            | properties | no | [continent_name, country_iso_code, country_name, region_iso_code, region_name, city_name, location] * | geoip 조회를 target_field를 기반으로 추가되는 속성을 제어한다. |
            | ignore_missing | no | false | true거나 필드가 존재하지 않는 경우 프로세서는 문서를 수정하지 않고 종료된다. |
            | first_only | no | true | true일 경우 필드 배열을 포함 하더라도 처음 발견된 geoip 데이터만 반환된다. |
            | download_database_on_pipeline_creation | no | true | true이거나 ingest.geoip.downloader.eager.download가 false인 경우 파이프라인이 생성될 때 누락된 데이터베이스가 다운로드된다.  |
    - Grok
        
        ### Grok processor
        
        - 문서 내의 단일 텍스트 필드에서 구조화된 필드를 추출한다.
        - 일치하는 필드를 추출할 필드와 일치할 것으로 예상되는 grok 패턴을 선택한다.
        - grok 패턴은 재사용할 수 있는 별칭 표현식을 지원하는 정규 표현식과 같다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | grok 표현식 구문 분석에 사용할 필드 |
            | patterns | yes | - | 명명된 캡처를 일치시키고 추출할 grok 표현식의 순서가 지정된 목록 |
            | pattern_definitions | no | - | 현재 프로세서에서 사용할 사용자 정의 패턴을 정의하는 패턴 이름 및 패턴 튜플의 맵 
            기존 이름과 일치하는 패턴은 기존 정의를 재정의한다. |
            | ecs_compatibility | no | disabled | disabled이거나 v1이어야 한다. 
            v1인 경우 프로세서는 ECS 필드 이름이 있는 패턴을 사용한다 |
            | trace_match | no | false | true인 경우 _ingest._grok_match_index는 일치하는 패턴에서 발견된 패턴에 대한 인덱스와 함께 일치하는 문서의 메타 데이터에 삽입된다.  |
            | ignore_missing | no | false | true이거나 field가 존재하지 않거나 null인 경우 프로세서는 문서를 수정하지 않고 종료된다. |
            | description | no | - | 프로세서에 대한 설명  |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - Gsub
        
        ### Gsub processor
        
        - 정규식과 대체(replacement)를 적용하여 문자열 필드를 반환한다. 필드가 문자열 배열인 경우 배열의 모든 구성이 반환된다. 문자열이 아닌 값이 발견되면 프로세서는 예외를 발생시킨다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 교체를 적용할 필드 |
            | pattern | yes | - | 교체할 패턴 |
            | replacement | yes | - | 일치하는 패턴을 바꿀 문자열 |
            | target_field | no | field | 변환된 값을 할당할 필드는 기본적으로 field 내부에서 업데이트된다  |
            | ignore_missing | no | false | true 및 field가 존재하지 않거나 null인 경우 프로세서는 문서를 수정하지 않고 종료한다 |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부로 프로세서를 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - HTML strip
        
        ### HTML strip processor
        
        - 필드에서 HTML 태그를 제거한다. 필드가 문자열 배열인 경우 배열의 모든 구성원에서 HTML 태그가 제거된다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | HTML 태그를 제거할 문자열 값 필드 |
            | target_field | no | field | 값을 할당할 필드는 기본적으로 field 내부에서 업데이트된다 |
            | ignore_missing | no | false | true이거나 필드가 존재하지 않을 경우 프로세서는 문서를 수정하지 않고 종료한다 |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부로 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - Interface
        
        ### Inference processor
        
        - 사전 훈련된 데이터 프레임 분석 모델 또는 자연어 처리 작업용으로 배포된 모델을 사용하여 파이프라인에서 수집되는 데이터를 추론한다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | model_id . | yes | - | (String) 학습된 모델의 ID 또는 별칭 또는 배포 ID |
            | target_field | no | ml.inference.<processor_tag> | (String) 결과 객체를 포함하기 위해 수신 문서에 추가된 필드 |
            | field_map | no | If defined the model’s default field map | (Object) 문서 필드 이름을 모델의 알려진 필드 이름에 매핑한다. 이 매핑은 모델 구성에 제공된 기본 매핑보다 우선한다. |
            | inference_config | no | The default settings defined in the model | (Object) 추론 유형 및 해당 옵션을 포함 |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부로 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - Join
        
        ### Join processor
        
        - 각 요소 사이에 구분 문자를 사용하여 배열의 각 요소를 단일 문자열로 결합한다. 필드가 배열이 아닌 경우 오류가 발생한다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 조인할 배열 값이 포함된 필드 |
            | separator | yes | - | 구분 문자 |
            | target_field | no | field | 결합된 값을 할당할 필드는 기본적으로 field 내부에서 업데이트된다 |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - JSON
        
        ### JSON processor
        
        - JSON 문자열을 구조화된 JSON 개체로 반환한다
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 구문 분석할 필드 |
            | target_field | no | field | 변환된 구조화된 객체가 기록될 필드 
            기존 콘텐츠를 덮어쓴다 |
            | add_to_root | no | false | 구문 분석된 JSON을 문서의 최상위 수준에 강제로 추가하는 플래그 
            target_field 옵션을 선택한 경우 설정하면 안 된다. |
            | add_to_root_conflict_strategy | no | replace | replace로 설정하면 구문 분석된 JSON의 필드와 충돌하는 루트 필드가 재정의된다. 
            merge로 설정하면 충돌하는 필드가 병합된다. 
            add_to_root가 true로 설정된 경우에만 적용된다. |
            | allow_duplicate_keys | no | false | true로 설정하면 JSON에 중복키가 포함되어 있어도 JSON 구문 분석기가 실패하지 않는다. 단, 중복 키에 대해서는 마지막 값이 우선한다. |
            | strict_json_parsing | no | true | true로 설정하면 JSON 파서가 필드 값을 엄격하게 구문 분석한다. 
            false는 구문 분석기가 관대해지지만 필드 값의 일부를 삭제할 가능성도 높아진다.  |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - KV
        
        ### KV processor
        
        - 다양한 메시지(또는 특정 이벤트 필드)를 자동으로 구문 분석하는데 도움이 된다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 구문 분석할 필드 |
            | field_split | yes | - | 키-값 쌍을 분할하는 데 사용할 정규식 패턴 |
            | value_split | yes | - | 키-값 쌍 내의 값에서 키를 분할하는 데 사용할 정규식 패턴 |
            | target_field | no | null | 추출된 키를 삽입할 필드 
            기본 값은 문서의 루트 |
            | include_keys | no | null | 문서에 필터링하고 삽입할 키 목록 
            기본 값은 모든 키 포함 |
            | exclude_keys | no | null | 문서에서 제외할 키 목록 |
            | ignore_missing | no | false | true 및 field가 존재하지 않거나 null인 경우 프로세서는 문서를 수정하지 않고 종료된다. |
            | prefix | no | null | 추출된 키에 추가할 접두사 |
            | trim_key | no | null | 추출된 키에서 잘라낼 문자열 |
            | trim_value | no | null | 추출된 값에서 잘라낼 문자열 |
            | strip_brackets | no | false | true일 경우 괄호, <>, [] 및 추출된 값에서 따움표 및 쌍따움표를 인용한다 |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서 식별자 |
    - Lowercase
        
        ### Lowercase processor
        
        - 문자열을 해당하는 소문자로 변환한다. 필드가 문자열 배열인 경우 배열의 모든 구성원이 반환된다.
        
        | Name | Required | Default | Description |
        | --- | --- | --- | --- |
        | field | yes | - | 소문자로 만드는 필드 |
        | target_field | no | field | 변환된 값을 할당할 필드는 기본적으로 field 내부에서 업데이트 된다.  |
        | ignore_missing | no | false | true 및 field가 존재하지 않거나 null인 경우 프로세서는 문서를 수정하지 않고 종료된다.  |
        | description | no | - | 프로세서에 대한 설명 |
        | if | no | - | 조건부 프로세서 실행 |
        | ignore_failure | no | false | 프로세서 오류 무시 |
        | on_failure | no | - | 프로세서 오류 처리 |
        | tag | no | - | 프로세서 식별자 |
    - Network direction
        
        ### Network direction processor
        
        - 소스 IP 주소, 대상 IP 주소 및 내부 네트워크 목록을 바탕으로 네트워크 방향을 계산한다.
        - 네트워크 방향 프로세서는 기본적으로 ECS 필드에서 IP주소를 읽는다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | source_ip | no | source.ip | 소스 IP 주소가 포함된 필드 |
            | destination_ip | no | destination.ip | 대상 IP 주소가 포함된 필드 |
            | target_field | no | network.direction | 네트워크 방향에 대한 출력 필드 |
            | internal_networks | yes * |  | 내부 네트워크 목록. CIDR 표기법으로 IPv4 및 IPv6 주소와 범위를 지원한다.
            internal_networks 또는 inter_networks_filed 중 하나만 지정해야 한다. |
            | internal_networks_field | no |  | internal_networks 구성을 읽을 지정된 문서의 필드 |
            | ignore_missing | no | true | ture이면서 필수 필드가 누락된 경우 프로세서는 문서를 수정하지 않고 종료한다 |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - Pipeline
        
        ### Pipeline processor
        
        - 다른 파이프라인을 실행한다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | name | yes | - | 실행할 파이프라인의 이름 |
            | ignore_missing_pipeline | no | false | 실패하는 대신 누락된 파이프라인을 무시할지 여부 |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류 무시 |
            | on_failure | no | - | 프로세서 오류 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - Redact
        
        <aside>
        💡 미리 보기 기술이며 향후 릴리스에서 변경되거나 제거될 수 있다.
        
        </aside>
        
        ### Redact processor
        
        - Redact 프로세서는 Grok 규칙 엔진을 사용하여 주어진 Grok 패턴과 일치하는 입력 문서의 텍스트를 모호하게 만든다. 프로세서는 이메일이나 IP 주소와 같은 알려진 패턴을 감지하도록 구성하여 개인 식별 정보(PII)를 모호하게 만드는 데 사용될 수 있다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 수정할 필드 |
            | patterns | yes | - | 명명된 캡처를 일치시키고 수정할 grok 표현식 목록 |
            | pattern_definitions | no | - | 프로세서에서 사용할 사용자 정의 패턴을 정의하는 패턴 이름 및 패턴 튜플의 맵 |
            | prefix | no | < | 이 토큰으로 수정된 섹션을 시작한다 |
            | suffix | no | > | 이 토큰으로 수정된 섹션을 종료한다 |
            | ignore_missing | no | true | true 및 field가 존재하지 않거나 null인 경우 프로세서는 문서를 수정하지 않고 종료된다. |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서의 식별자 |
            | skip_if_unlicensed | no | false | true이면서 현재 라이센스가 수정 프로세서 실행을 지원하지 않는 경우 프로세서는 문서를 수정하지 않고 종료된다.  |
    - Registered domain
        
        ### Registered domain processor
        
        - FQDN(정규화된 도메인 이름)에서 등록된 도메인(유효 최상위 도메인 또는 etLD라고도 한다), 하위 도메인 및 최상위 도메인을 추출한다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes |  | 소스 FQDN이 포함된 필드 |
            | target_field | no | <empty string> | 추출된 도메인 구성 요소가 포함된 개체 필드. <empty string>인 경우 프로세서는 문서의 루트에 구성 요소를 추가한다.  |
            | ignore_missing | no | true | 필수 필드가 누락된 경우 true 프로세서는 문서를 수정하지 않고 자동으로 종료된다. |
            | description | no | - | 프로세서에 대한 설명.  |
            | if | no | - | 조건부로 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - Remove
        
        ### Remove processor
        
        - 기존 필드를 제거한다. 필드가 하나도 존재하지 않으면 예외가 발생한다.
        
        | Name | Required | Default | Description |
        | --- | --- | --- | --- |
        | field | yes | - | 제거할 필드 |
        | ignore_missing | no | false | true 및 field가 존재하지 않거나 null인 경우 프로세서는 문서를 수정하지 않고 종료된다. |
        | keep | no | - | 유지해야 할 필드. 설정되면 지정된 필드 이외의 모든 필드가 제거된다. |
        | description | no | - | 프로세서에 대한 설명 |
        | if | no | - | 조건부 프로세서 실행 |
        | ignore_failure | no | false | 프로세서 오류를 무시 |
        | on_failure | no | - | 프로세서 오류를 처리 |
        | tag | no | - | 프로세서의 식별자 |
    - Rename
        
        ### Rename processor
        
        - 기존 필드의 이름을 바꾼다. 필드가 존재하지 않거나 새 이름이 이미 사용된 경우 예외가 발생한다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 이름을 바꿀 필드 |
            | target_field | yes | - | 필드의 새 이름 |
            | ignore_missing | no | false | true 및 field가 존재하지 않을 경우 프로세서는 문서를 수정하지 않고 종료된다. |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서 식별자 |
    - Reroute
        
        ### Reroute processor
        
        - reroute 프로세서를 사용하면 문서를 다른 대상 인덱스 또는 데이터 스트림으로 라우팅할 수 있다. 두 가지 주요 모드가 있다.
        - 옵션 설정 시 destination 대상이 명시적으로 지정되며 dataset 및 namespace 옵션은 설정할 수 없다.
        - destination 옵션이 설정되지 않은 경우 프로세서는 데이터 스트림 모드에 있다. 이 모드에서 reroute 프로세서는 데이터 스트림 명령 체계를 따르는 데이터 스트림에서만 사용할 수 있다.
        - 호환되지 않는 이름을 가진 데이터 스트림에서 reroute 프로세서를 사용하려고 하면 예외가 발생한다
        - 데이터 스트림의 이름은 <type>-<dataset>-<namespace>의 세 부분으로 구성된다.
        - 프로세서는 문서의 정적 값이나 참조 필드를 모두 사용하여 새 대상의 데이터 세트 및 네임스페이스 구성 요소를 결정할 수 있다
        - reroute 프로세서를 사용하여 데이터 스트림 유형을 변경할 수 없다
        - reroute 프로세서가 실행된 후에는 최종 파이프라인을 포함하여 현재 파이프라인의 다른 모든 프로세서를 건너뛴다.
        - 현재 파이프라인의 컨텍스트에서 실행되는 경우 호출 파이프라인더 건너뛴다.
        - reroute 프로세서는 data_stream.<type|dataset|namespace> 필드가 새 대상에 따라 설정되도록 보장한다. 문서에 event.dataset 값이 포함되어 있으면 data_stream.dataset과 동일한 값을 반영하도록 업데이트된다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | destination | no | - | 대상의 정적 값. dataset 또는 namespace 옵션이 설정된 경우 설정할 수 없다. |
            | dataset | no | {{data_stream.dataset}} | 데이터 스트림 이름의 데이터 세트 부분에 대한 필드 참조 또는 정적 값.  |
            | namespace | no | {{data_stream.namespace}} | 데이터 스트림 이름의 네임스페이스 부분에 대한 필드 참조 또는 정적 값.  |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - Script
        
        ### Script processor
        
        - 수신 문서에 대해 인라인 또는 저장된 스크립트를 실행한다. 스크립트는 ingest 컨텍스트에서 실행된다.
        - 스크립트 프로세서는 스크립트 캐시를 사용하여 각 수신 문서에 대한 스크립트 재컴파일을 방지한다. 성능을 향상시키려면 프로덕션에서 스크립트 프로세서를 사용하기 전에 스크립트 캐시의 크기가 적절하게 조정되었는지 확인해야 한다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | lang | no | "painless" | 스크립트 언어 |
            | id | no | - | 저장된 스크립트의 ID. no source를 지정하는 경우 매개변수는 필수가 된다. |
            | source | no | - | 인라인 스크립트. no id를 지정하는 경우 매개변수는 필수가 된다. |
            | params | no | - | 스크립트에 대한 매개변수가 포함된 개체 |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - Set
        
        ### Set processor
        
        - 하나의 필드를 설정하고 이를 지정된 값과 연결한다. 필드가 이미 존재하는 경우 해당 값은 제공된 값으로 대체된다.
        
        | Name | Required | Default | Description |
        | --- | --- | --- | --- |
        | field | yes | - | 삽입, 업데이트 또는 업데이트할 필드 |
        | value | yes* | - | 필드에 설정할 값
        value 또는 copy_from 중 하나만 지정할 수 있다. |
        | copy_from | no | - | field에 복사될 원본 필드는 value와 동시에 설정할 수 없다. |
        | override | no | true | true이거나 프로세서가 기존의 null값이 아닌 필드로 필드를 업데이트하는 경우, false 로 설정하면 해당 필드는 건드리지 않는다. |
        | ignore_empty_value | no | false | true이고 null 또는 빈 문자열로 평가되는 템플릿 조각인 값과 함께 사용되는 경우 프로세서는 문서를 수정하지 않고 종료된다. 
        마찬가지로, copy_from과 함께 사용하면 필드가 존재하지 않거나 해당 값이 null 또는 빈 문자열로 평가되면 자동으로 종료된다.  |
        | media_type | no | application/json | 인코딩 값의 미디어 유형 값이 템플릿 조각인 경우에만 적용된다. 
        application/json, text/plain, application/x-www-form-urlencoded 중 하나여야 한다. |
        | description | no | - | 프로세서에 대한 설명 |
        | if | no | - | 조건부 프로세서 실행 |
        | ignore_failure | no | false | 프로세서 오류 무시 |
        | on_failure | no | - | 프로세서 오류 처리 |
        | tag | no | - | 프로세서의 식별자 |
    - Set security user
        
        ### Set security user processor
        
        - 수집을 전처리하여 현재 인증된 사용자의 사용자 관련 세부 정보를 현재 문서로 설정한다.
        - api_key 속성은 사용자가 API 키로 인증하는 경우에만 존재한다. API 키의 ID, 이름, 메타데이터 필드를 포함하는 객체이다.
        - 영역 속성은 이름과 유형이라는 두 개의 필드가 있는 객체이기도 하다.  API 키 인증을 사용할 때 영역 속성은 API 키가 생성되는 영역을 나타낸다.
        - 인증 유형 속성은 REALM, API_KEY, TOKEN 및 ANONYMOUS에서 값을 가져올 수 있는 문자열이다.
        - 색인 요청을 위해서는 인증된 사용자가 필요하다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 사용자 정보를 저장할 필드 |
            | properties | no | [username, roles, email, full_name, metadata, api_key, realm, authentication_type] | field에 추가되는 사용자 관련 속성을 제어한다. |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - Sort
        
        ### Sort processor
        
        - 배열의 요소를 오름차순 또는 내림차순으로 정렬한다. 동종 숫자 배열은 숫자순으로 정렬되고, 문자열 배열 또는 문자열 + 숫자의 이종 배열은 사전순으로 정렬된다.
        - 필드가 배열이 아닌 경우 오류가 발생한다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 정렬할 필드 |
            | order | no | "asc" | 사용할 정렬 순서 |
            | target_field | no | field | 정렬된 값을 할당할 필드는 기본적으로 field 내부에서 업데이트 된다. |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - Split
        
        ### Split processor
        
        - 구분 문자를 사용하여 필드를 배열로 분할한다. 문자열 필드에서만 작동한다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 분할할 필드 |
            | separator | yes | - | 구분 기호와 일치하는 정규식 |
            | target_field | no | field | 분할 값을 할당할 필드는 기본적으로 field 내부에서 업데이트된다. |
            | ignore_missing | no | false | true 및 field가 존재하지 않는 경우 프로세서는 문서를 수정하지 않고 조용히 종료된다.  |
            | preserve_trailing | no | false | 빈 후행 필드를 유지한다. |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부로 프로세서를 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리  |
            | tag | no | - | 프로세서의 식별자 |
    - Trim
        
        ### Trim processor
        
        - 필드에서 공백을 잘라낸다. 필드가 문자열 배열인 경우 배열의 모든 구성원이 잘린다.
        - 이는 선행 및 후행 공백에서만 작동한다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 공백을 잘라낼 문자열 값 필드 |
            | target_field | no | field | 기본적으로 잘린 값을 할당할 필드는 field 내부에서 업데이트된다. |
            | ignore_missing | no | false | true 및 field가 존재하지 않을 경우 프로세서는 문서를 수정하지 않고 종료된다. |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부로 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류 무시 |
            | on_failure | no | - | 프로세서 오류 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - Uppercase
        
        ### Uppercase processor
        
        - 문자열을 해당하는 대문자로 변환한다. 필드가 문자열 배열인 경우 배열의 모든 구성원이 변환된다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 대문자로 만들 필드 |
            | target_field | no | field | 변환된 값을 할당할 필드는 기본적으로 field 내부에서 업데이트 된다 |
            | ignore_missing | no | false | true 및 field가 존재하지 않거나 field가 null값인 경우 프로세서는 문서를 수정하지 않고 종료된다. |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부로 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서 식별자 |
    - URL decode
        
        ### URL decode processor
        
        - 문자열을 URL로 디코딩한다. 필드가 문자열 배열인 경우 배열의 모든 구성원이 디코딩된다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 디코딩할 필드 |
            | target_field | no | field | 변환된 값을 할당할 필드는 기본적으로 field 내부에서 업데이트된다. |
            | ignore_missing | no | false | true 및 field가 존재하지 않거나 null인 경우 프로세서는 문서를 수정하지 않고 종료된다. |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - URI parts
        
        ### URI parts processor
        
        - URI(Uniform Resource Identifier) 문자열을 구문 분석하고 해당 구성 요소를 객체로 추출한다. URI 개체에는 URI의 도메인, 경로, 조각, 포트, 쿼리, 구성표, 사용자 정보, 사용자 이름 및 비밀번호에 대한 속성이 포함되어 있다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | URI 문자열이 포함된 필드 |
            | target_field | no | url | URI 객체의 출력 필드 |
            | keep_original | no | true | true이면 프로세서는 구문 분석되지 않은 URI를 <target_field>.original에 복사한다 |
            | remove_if_successful | no | false | true이면 프로세서는 field URI 문자열을 구문 분석한 후 field를 제거한다. 구문 분석에 실패하면 프로세서는 field를 제거하지 않는다. |
            | ignore_missing | no | false | true이거나 field가 존재하지 않는 경우 프로세서는 문서를 수정하지 않고 종료된다.  |
            | description | no | - | 프로세서에 대한 설명 |
            | if | no | - | 조건부 프로세서 실행 |
            | ignore_failure | no | false | 프로세서 오류를 무시 |
            | on_failure | no | - | 프로세서 오류를 처리 |
            | tag | no | - | 프로세서의 식별자 |
    - User agent
        
        ### User agent processor
        
        - user_agent 프로세서는 브라우저가 웹 요청과 함께 보내는 사용자 에이전트 문자열에서 세부 정보를 추출한다. 이 프로세서는 기본적으로 이 정보를 user_agent 필드 아래에 추가한다.
        - ingest-user-agent 모듈은 기본적으로 Apache 2.0 라이센스와 함께 uap-java에서 사용할 수 있는 regexes.yaml과 함께 제공된다.
            
            
            | Name | Required | Default | Description |
            | --- | --- | --- | --- |
            | field | yes | - | 사용할 에이전트 문자열이 포함된 필드 |
            | target_field | no | user_agent | 사용자 에이전트 세부정보로 채워지는 필드 |
            | regex_file | no | - | config/ingest-user-agent 사용자 에이전트 문자열을 구문 분석하기 위한 정규식이 포함된 디렉터리의 파일 이름. 
            Elasticsearch를 시작하기 전에 디렉터리와 파일을 모두 생성해야 한다. 
            지정하지 않으면 ingest-user-agent는 함께 제공되는 uap-core의 regexex.yaml을 사용한다 |
            | properties | no | [name, major, minor, patch, build, os, os_name, os_major, os_minor, device] | target_field에 추가되는 속성을 제어한다. |
            | extract_device_type | no | false | (베타) 최선을 다해 사용자 에이전트 문자열에서 장치 유형을 추출한다. |
            | ignore_missing | no | false | true이거나 field가 존재하지 않는 경우 프로세서는 문서를 수정하지 않고 종료된다.  |

# Aliases

- Aliasses
    
    ### Aliases
    
    - 별칭(Aliases)은 데이터 스트림 또는 인덱스 그룹의 보조 이름이다. 대부분의 Elasticsearch API는 데이터 스트림이나 인덱스 이름 대신 별칭을 허용한다.
    - 언제든지 별칭의 데이터 스트림이나 인덱스를 변경할 수 있다. 애플리케이션의 Elasticsearch 요청에 별칭을 사용하면 가동 중지 시간이나 앱 코드 변경 없이 데이터를 다시 색인화할 수 있다.
        
        ### Aliases types
        
        - 별칭에는 두 가지 유형이 있다.
            - 데이터 스트림 별칭은 하나 이상의 데이터 스트림을 가리킨다
            - 인덱스 별칭은 하나 이상의 인덱스를 가리킨다
        - 별칭은 데이터 스트림과 인덱스를 모두 가리킬 수 없다. 또한 데이터 스트림의 지원 인덱스를 인덱스 별칭에 추가할 수 없다.
        
        ### Add an alias
        
        - 기존 데이터 스트림이나 인덱스를 별칭에 추가하려면 별칭 API의 add 작업을 사용한다. 별칭이 없다면 요청에서 별칭을 생성한다
        - API index와 indices 매개변수는 와일드카드(*)를 지원한다. 데이터 스트림과 인덱스 모두와 일치하는 와일드카드 패턴은 오류를 반환한다.
            
            ```json
            POST _aliases
            {
              "actions": [
                {
                  "add": {
                    "index": "logs-*",
                    "alias": "logs"
                  }
                }
              ]
            }
            ```
            
        
        ### Remove an alias
        
        - 별칭 제거에는 별칭 API의 remove작업을 사용한다.
            
            ```json
            POST _aliases
            {
              "actions": [
                {
                  "remove": {
                    "index": "logs-nginx.access-prod",
                    "alias": "logs"
                  }
                }
              ]
            }
            ```
            
        
        ### Multiple actions
        
        - 별칭 API를 사용하면 단일 원자성 작업으로 여러 작업을 수행할 수 있다.
        - 스트림을 별칭으로 변경하는 작업 중 별칭은 가동 중지 시간이 없으며 동시에 두 스트림을 모두 가리키지도 않는다.
            
            ```json
            POST _aliases
            {
              "actions": [
                {
                  "remove": {
                    "index": "logs-nginx.access-prod",
                    "alias": "logs"
                  }
                },
                {
                  "add": {
                    "index": "logs-my_app-default",
                    "alias": "logs"
                  }
                }
              ]
            }
            ```
            
        
        ### Add an alias at index creation
        
        - 구성 요소나 인덱스 템플릿을 사용하여 인덱스나 데이터 스트림 별칭을 생성할 때 추가할 수도 있다.
        - 또는, 인덱스 생성 API 요청에서 인덱스 별칭을 지정할 수도 있다.
            
            ```json
            # PUT <my-index-{now/d}-000001>
            PUT %3Cmy-index-%7Bnow%2Fd%7D-000001%3E
            {
              "aliases": {
                "my-alias": {}
              }
            }
            ```
            
        
        ### View aliases
        
        - 클러스터 별칭 목록을 가져올 때는 인수 없이 별칭 가져오기 API를 사용한다.
            
            ```json
            GET _alias
            ```
            
        - 데이터 스트림이나 개별 인덱스를 보려면 별칭을 지정한다.
            
            ```json
            GET _alias/logs
            ```
            
        
        ### Write index
        
        - is_write_index를 사용하여 별칭에 대한 쓰기 인덱스 또는 데이터 스트림을 지정하는 데 사용할 수 있다. Elasticsearch는 별칭에 대한 모든 쓰기 요청을 이 인덱스 또는 데이터 스트림으로 라우팅한다.
            
            ```json
            POST _aliases
            {
              "actions": [
                {
                  "add": {
                    "index": "logs-nginx.access-prod",
                    "alias": "logs"
                  }
                },
                {
                  "add": {
                    "index": "logs-my_app-default",
                    "alias": "logs",
                    "is_write_index": true
                  }
                }
              ]
            }
            ```
            
        - 별칭이 여러 인덱스 또는 데이터 스트림을 가리키고 is_write_index가 설정되지 않은 경우 별칭은 쓰기 요청을 거부한다. 인덱스 별칭이 하나의 인덱스를 가리키고 is_write_index가 설정되지 않은 경우 해당 인덱스는 자동으로 쓰기 인덱스 역할을 한다. 데이터 스트림 별칭은 하나의 데이터 스트림을 가리키는 경우에도 쓰기 데이터 스트림을 자동으로 설정하지 않는다.
        
        ### Filter an alias
        
        - filter 옵션은 Query DSL을 사용하여 별칭이 액세스할 수 있는 문서를 제한한다.
            
            ```json
            POST _aliases
            {
              "actions": [
                {
                  "add": {
                    "index": "my-index-2099.05.06-000001",
                    "alias": "my-alias",
                    "filter": {
                      "bool": {
                        "filter": [
                          {
                            "range": {
                              "@timestamp": {
                                "gte": "now-1d/d",
                                "lt": "now/d"
                              }
                            }
                          },
                          {
                            "term": {
                              "user.id": "kimchy"
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
            ```
            
        
        ### Routing
        
        - 별칭에 대한 요청을 특정 샤드로 라우팅하려면 routing 옵션을 사용한다. 이를 통해 shard 캐시를 활용하여 검색 속도를 높일 수 있다. 데이터 스트림 별칭은 라우팅 옵션을 지원하지 않는다.
            
            ```json
            POST _aliases
            {
              "actions": [
                {
                  "add": {
                    "index": "my-index-2099.05.06-000001",
                    "alias": "my-alias",
                    "routing": "1"
                  }
                }
              ]
            }
            ```
            
        - 인덱싱 및 검색에 대해 서로 다른 라우팅 값을 지정하려면 index_routing 및 search_routing을 사용한다. 지정된 경우 이러한 옵션은 해당 작업의 routing 값을 덮어쓴다.
            
            ```json
            POST _aliases
            {
              "actions": [
                {
                  "add": {
                    "index": "my-index-2099.05.06-000001",
                    "alias": "my-alias",
                    "search_routing": "1",
                    "index_routing": "2"
                  }
                }
              ]
            }
            ```
            

# Search your data

- Search your data
    
    ### Search your data
    
    - 검색은 결합되어 Elasticsearch로 전송되는 하나 이상의 쿼리로 구성된다. 검색 쿼리와 일치하는 문서는 응답의 히트 또는 검색 결과로 반환된다.
    - 검색에는 쿼리를 더 잘 처리하는 데 사용되는 추가 정보가 포함될 수 있다.
        
        ### Run a search
        
        - 검색 API를 사용하여 Elasticsearch 데이터 스트림이나 인덱스에 저장된 데이터를 검색하고 집계할 수 있다. API의 query 요청 본문 매개변수는 Query DSL로 작성된 쿼리를 허용한다.
        
        ### Define fields that exist only in a query
        
        - 데이터를 인덱싱한 다음 검색하는 대신 검색 쿼리의 일부로만 존재하는 런타임 필드를 정의할 수 있다.
        - 검색 요청에 runtime_mappings 섹션을 지정하여 선택적으로 Painless 스크립트를 포함할 수 있는 런타임 필드를 정의한다.
        
        ### Common search options
        
        - 다음 옵션을 사용하여 검색을 사용자 정의할 수 있다.
            
            ### Query DSL
            
            - 쿼리 DSL은 원하는 결과를 얻기 위해 혼합하고 일치시킬 수 있는 쿼리 유형을 지원한다. 쿼리 유형은 다음과 같다.
                - 여러 기준에 따라 쿼리를 결합하고 결과를 일치시킬 수 있는 bool 및 기타 복합 쿼리
                - 정확히 일치하는 항목을 필터링하고 찾기 위한 용어 수준 쿼리
                - 검색 엔진에서 일반적으로 사용되는 전체 텍스트 쿼리
                - 지리 및 공간 쿼리
            
            ### Aggreagations
            
            - 검색 집계를 사용하여 검색 결과에 대한 통계 및 기타 분석을 얻을 수 있다.
            
            ### Search multiple data streams and indices
            
            - 쉼표로 구분된 값과 grep 같은 인덱스 패턴을 사용하여 동일한 요청에서 여러 데이터 스트림과 인덱스를 검색할 수 있다.
            - 특정 인덱스의 검색 결과를 향상시킬 수도 있다.
            
            ### Paginate search results
            
            - 기본적으로 검색에는 일치하는 상위 10개 히트만 반환한다.
            
            ### Retrieve selected fields
            
            - 검색 응답의 Hits.hits 속성에는 각 히트에 대한 전체 문서 _source가 포함된다.
            
            ### Sort search results
            
            - 기본적으로 검색 결과는 각 문서가 쿼리와 얼마나 일치하는지 측정하는 관련성 점수인 _score를 기준으로 정렬된다.
            
            ### Run an async search
            
            - Elasticsearch 검색은 기본적으로 동기식으로 작동한다. 검색 요청은 응답을 반환하기 전에 완전한 결과를 기다린다.
            - 그러나 대규모 세트나 여러 클러스터를 검색하는 경우 완전한 결과를 얻는 데 시간이 더 오래 걸릴 수 있다.
            - 오랜 대기 시간을 피하기 위해 비동기식 또는 비동기식 검색을 실행할 수 있다.
        
        ### Search timeout
        
        - 기본적으로 검색 요청은 시간 초과되지 않는다. 요청은 응답을 반환하기 전에 각 샤드의 완전한 결과를 기다린다.
        - 비동기 검색은 장기 실행 검색을 위해 설계되었지만 timeout 매개변수를 사용하여 각 샤드가 완료될 때까지 기다리는 기간을 지정할 수도 있다. 각 샤드는 지정된 기간 내에 조회수를 수집한다.
        - 기간 종료 시 수집이 완료되지 않은 경우 Elasticsearch는 해당 시점까지 누적된 조회수만 사용한다. 검색 요청의 전체 대기 시간은 검색에 필요한 샤드 수와 동시 샤드 요청 수에 따라 달라진다.
            
            ```json
            GET /my-index-000001/_search
            {
              "timeout": "2s",
              "query": {
                "match": {
                  "user.id": "kimchy"
                }
              }
            }
            ```
            
        - 모든 검색 요청에 대해 클러스터 전체의 기본 제한 시간을 설정하려면 클러스터 설정 API를 search.default_search_timeout을 사용하여 구성할 수 있다. 요청에 인수가 전달되지 않은 경우 이 전역 제한 시간이 사용된다.
        
        ### Search cancellation
        
        - 작업 관리 API를 사용하여 검색 요청을 취소할 수 있다. 또한 Elasticsearch는 클라이언트의 HTTP 연결이 종료되면 자동으로 검색 요청을 취소한다.
        - 검색 요청이 중단되거나 시간 초과되면 HTTP 연결을 닫도록 클라이언트를 설정하는 것이 권장된다.
        
        ### Track total hits
        
        - 일반적으로 일치하는 항목을 모두 방문하지 않으면 총 적중 횟수를 정확하게 계산할 수 없으며, 이는 많은 문서와 일치하는 쿼리의 경우 비용이 많이 든다. 매개변수 tarack_total_hits 변수를 사용하면 총 적중 횟수를 추적하는 방법을 제어할 수 있다.
            
            ```json
            GET my-index-000001/_search
            {
              "track_total_hits": true,
              "query": {
                "match" : {
                  "user.id" : "elkbee"
                }
              }
            }
            ```
            
        
        ### Quickly check for matching docs
        
        - 특정 쿼리와 일치하는 문서가 있는지만 알고 싶다면 검색 결과에 관심이 없음을 나타내도록 size를 설정할 수 있다.
- Collapse search results
    
    ### Collapse search results
    
    ### Expand collapse results
    
    ### Collapsing with search_after
    
    ### Sencode level of collapsing
    
    ### Track Scores
    
- Filter search results
- Highlighting
- Long-running searches
- Near-real time search
- Paginate search results
- Retrieve inner hits
- Retrieve selected fields
- Search across clusters
- Search multiple data streams and indices
- Search shard routin
- Search templates
- Search with synonyms
- Sort search results
- kNN search
- Semantic search
- Searching with query rules

# Query DSL

# Aggregations

# Geospatial analysis

# EQL

# SQL

# Scripting

# Data management

# Autoscaling

# Monitor a cluster

# Roll up or transform your data

# Set up a cluster for high availability

# Snapshot and resotre

# Secure the Elastic Stack

# Watcher

# Command line tools

# How to

# Troubleshooting

# RSET APIs

# Migration guide

# Release notes

# Dependencies and versions