# 생활코딩 WEB 2 - CSS

# CSS 소개

- 1.  수업 소개
    - 오리엔테이션
    - 웹이 등장한 이후, 최초의 기술인 HTML
    - HTML의 디자인적인 불만족에서 만들어진 코드를 분리한 것이 CSS
    - 디자인 자체보다는, 기술적 능력에 가깝다.
- 2.  CSS가 등장하기 전의 상황
    - 기존의 HTML에 디자인적 언어를 추가하는 쉬운 길에서 한계에 도착, CSS라는 새로운 언어를 만드는 어렵지만 본질적인 길로 나아간 발달의 과정
        
        ```css
        <font clolr="red">
        </font>
        ```
        
    - 이와 같은 HTML 안에서의 CSS태그는 불필요한, 중복되는 정보의 추가로 인해 그 가치가 떨어진다.
- 3.  CSS의 등장
    - <style> </style> 태그 : 그 사이의 코드가 CSS 태그라는 의미.
        
        ```css
        <style>
        a {
        color:red;
        }
        </style>
        ```
        
    - 이 문서의 모든 a태그에 대해 color:red를 적용하라는 의미.
    여기서 a를 선택자(Selector)
    color:red; 를 효과(declaration)라 칭한다.
    - 이와 같은 방식으로의 발달이 가져온 가장 큰 효과는 중복의 제거와 효율적인 사용
    - 그리고 정보의 구별(HTML은 정보에 전념, 디자인을 CSS라는 영역에 전담)에 있다.

# CSS 기본 문법과 속성

- 4.  CSS의 기본 문법
    - style=”” : <style></style> 태그 밖에서, 특정한 코드 하나에만 CSS효과를 주고 싶을 때 사용
    
    ```css
    <a href="2.html" style="color:red">CSS</a>
    ```
    
    - CSS 문자열에 color:red 라는 효과를 부여.
    이 같은 방식은 대상, 효과를 직접 지정하기에 선택자를 지정하지 않아도 된다.
    - text-decoration: 
    텍스트에 밑줄을 그어주는 효과
    - text-decoration: none;
    해당 태그에 style=””를 통해 부여된 CSS효과를 제외한, 아무런 효과도 부여하지 않는다.
- 5.  혁명적 변화
    
    ```css
    a { 
    color:red;
    }
    ```
    
    - 위 태그에서 
    a → Selector(선택자)
    color:red; → Declaration(효과)
    color → Property(속성)
    red; → Property Value(속성값)
    - 겁색의 중요성 강의
- 6.  CSS속성을 스스로 알아내기
    - 검색으로 원하는 Property 값을 찾아내는 방법 강의
- 7.  CSS 선택자의 기본
    - CSS 명령권의 순서는
    태그 선택자 < Class 선택자 < id 선택자 순서로 진행된다.
    - 동일한 명령권일 때는 class 값으로부터 가까운 CSS의 명령을 받는다.
    - class=”” 은 “”안의 이름으로 그룹핑하는 것. 이는 CSS가 아닌 HTML 함수다.
    - 이후, .saw{} 방식으로 선택자에 변수값을 부여할 때 사용된다.

# 박스모델과 그리드

- 8.  박스모델
    - border를 통해 박스를 만드는 방법.
    - 화면 전체를 쓰는 태그(block level element) 와 컨텐츠 크기만큼을 쓰는 태그(inline element)가 있다.
    - 단, 이는 고정되어 있는 것이 아닌, display:inline(block) 명령어로 변경이 가능하다.
    - 동일한 내용의 CSS라면 각 선택자 사이에 ,를 삽입함으로서 중복을 제거하고 동일한 효과 부여가 가능하다.
- 9.  박스모델 써먹기
    - 이전에 배웠던 내용들로 CSS 코드 생성하기
    
    ```css
    <style>
    body{
      margin:0;
    }
    a {
      color:black;
      text-decoration: none;
    }
    h1 {
      font-size: 45px;
      text-align: center;
      border-bottom:1px solid gray;
      margin: 0;
      padding:20px
    }
    
    ol{
      border-right:1px solid gray;
      width:100px;
      margin: 0px;
      padding: 20px;
    }
    #grid{
      display: grid;
      grid-template-columns: 150px 1fr;
    }
    #grid ol{
      padding-left: 33px;
    }
    #article{
      padding-left:25px;
    }
    @media(max-width:800px){
      #grid{
      display:block;
        }
        ol{
          border-right:none;
        }
        h1{
          border-bottom:none;
        }
    </style>
    ```
    
- 10. 그리드 소개
    - <div></div> 태그 소개
    디비전(division) 태그. 함수 값 없이 디자인을 위해 코드들을 지정하는 태그
    - <span></span> 위의 <div>태그 처럼 디자인을 위한 태그
    - grid 태그 사용 방법
    
    ```css
    #grid{ 
    
    border:5px solid pinl; 
    
    display:grid; 
    
    grid-template-columns: 150px 1fr; 
    
    }
    ```
    
    - grid는 각각의 컨텐츠를 특정 크기와 나머지로 구분하여 배치해준다.
- 11. 그리드 써먹기
    - 앞선 내용들을 바탕으로 코드 작성 해 보기
    - #”” ol{}
    ””이라는 id값을 가진 태그를 상위 디렉토리로 둔 ol 함수에게 {} 안의 CSS 명령을 부여한다.

# 미디어 쿼리와 마무리

- 12. 미디어 쿼리 소개
    - 반응형 웹 디자인(responsive Web) : 사용자의 조작에 반응하여 디자인이 변하는 것.
    - @media(){} 태그
    ()안의 조건일 때, {}안의 CSS효과를 실행한다.
    
    ```css
    @media(min-width:800px) { 
    
          div 
    
    {display:none;} 
    
      }
    ```
    
    - width값이 800px 이상일 때 {display :none;} 효과를 부여한다.
- 13. 미디어 쿼리 써먹기
    - 앞서 배운 내용을 기반으로 한 코드 작성하기
    
    ```css
    @media(max-width:800px){
      #grid{
      display:block;
        }
        ol{
          border-right:none;
        }
        h1{
          border-bottom:none;
        }
    ```
    
- 14. CSS코드의 재사용
    - <Link rel=”” href=””> 태그
    첫 번째 “”안의 형식으로 두 번째 “”안의 파일을 불러온다는 태그
    - 이 태그를 통해 HTML 문서로부터 CSS 문서를 분리할 수 있다.
    - 이는 곧 중복의 제거와 효율성의 증가.
- 15. 수업을 마치며
    - 속성을 많이 알 수록 더 풍부한 표현력을, 선택자를 많이 알 수록 속성을 더 정확하게 표현할 수 있게 된다.