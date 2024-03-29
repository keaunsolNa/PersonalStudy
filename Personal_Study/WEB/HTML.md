# HTML

# 1. Orientation

- 인터넷
    - 전 세계의 컴퓨터들이 네트워크를 통해 연결되어 정보를 공유하는데 목적을 두며, 프로토콜을 이용해 통신한다.
        
        <aside>
        💡 전길남 박사 [https://ko.wikipedia.org/wiki/전길남](https://ko.wikipedia.org/wiki/%EC%A0%84%EA%B8%B8%EB%82%A8)
        
        </aside>
        
- 웹
    
    <aside>
    💡 팀 버너스리  [https://ko.wikipedia.org/wiki/팀_버너스리](https://ko.wikipedia.org/wiki/%ED%8C%80_%EB%B2%84%EB%84%88%EC%8A%A4%EB%A6%AC)
    
    </aside>
    
    - 인터넷에 연결된 컴퓨터를 통해 사람들이 정보를 공유할 수 있는 공간으로 인터넷의 통신망 위에서 작동하는 서비스.
        
        ![Untitled](HTML/Untitled.png)
        
    - 웹의 처리 과정: 네트워크를 통해 서로 연결된 컴퓨터들 간에 서버와 클라이언트의 역할을 나누고, HTML을 통해 정보와 자료를 주고받는 시스템
        
        ![Untitled](HTML/Untitled%201.png)
        
    - 웹의 특징:
    1. HTTP(Hyper Text Transfer Protocol) 사용
    2. HTML(Hyper Text Markup Language)로 작성된 문서 연결
    3. 텍스트, 그래픽, 오디오, 비디오, 프로그램 파일 등 멀티미디어 서비스를 제공
    - 반응형 웹: 웹 서버에서 제공되는 정보가 다양한 기기(컴퓨터, 핸드폰, 태블릿 등)에 맞춰서 제공되는 기술
- HTML의 개념과 정의
    - 웹에서 정보를 표현할 목적으로 만든 마크 업 언어(Hyper Text Markup Language) 
    → 마크 업(태그): 문서의 논리적인 구조를 정의하고, 출력장치에 어떠한 형태로 보여질 것인지를 지시하는 역할
    → 마크 업 언어(Markup Language): 마크 업(태그)의 형식과 규칙을 정의한 언어.
        
        ![Untitled](HTML/Untitled%202.png)
        
        ![Untitled](HTML/Untitled%203.png)
        
    - HTML 주의사항
    1. 태그는 대소문자를 구분하지 않지만 관례상 소문자를 권장한다.
    2. 시작태그로 시작하면 반드시 종료태그로 종료를 해야 한다. 
    → <HTML> </HTML>
    3. 파일 확장자는 반드시 html.html으로 설정해야 한다.
    4. 문자의 공백은 한 개만 인식을 하고, 공백을 추가하기 위해서는 특수 기호(&nbsp;)를 이용해야 한다.
    
    ![Untitled](HTML/Untitled%204.png)
    
- 프로그램 설치
    - VSCode 프로그램 사용
- 실행 결과 출력(tasks.json)
    
    ```html
    {
        "version": "2.0.0",
        "tasks": [
            {
                "label": "Chrome",
                "type": "process",
                "command": "chrome.exe",
                "windows": {
                    "command": "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"
                },
                "args": [
                    "${file}"
                ],
                "problemMatcher": [],
                "group": {
                    "kind": "build",
                    "isDefault": true
                }
            }
        ]
    }
    ```
    

# 2. 각종 태그

- HTML5 페이지 구조
    
    ![Untitled](HTML/Untitled%205.png)
    
    ![Untitled](HTML/Untitled%206.png)
    
    ![Untitled](HTML/Untitled%207.png)
    
    ![Untitled](HTML/Untitled%208.png)
    
    ![Untitled](HTML/Untitled%209.png)
    
    ![Untitled](HTML/Untitled%2010.png)
    
    ![Untitled](HTML/Untitled%2011.png)
    
- HTML5 구조 관련 태그
    - HEAD 태그
        
        ![Untitled](HTML/Untitled%2012.png)
        
        ![Untitled](HTML/Untitled%2013.png)
        
    - <title></title>
        - 페이지의 제목을 나타내는 태그
    - <script></script>
        - 페이지에서 스크립트를 사용하기 위해 사용한다.
        head와 body 두 곳에서 사용할 수 있다.
            
            ![Untitled](HTML/Untitled%2014.png)
            
            ![Untitled](HTML/Untitled%2015.png)
            
    - <style></style>
        - 태그의 스타일을 지정해주는 태그. CSS속성들을 HTML 내에 직접 사용할 때(인라인) 사용한다.
    - <base>
        
        ![Untitled](HTML/Untitled%2016.png)
        
- Text 관련 태그
    - 문단 구분, 띄워쓰기 관련 태그
        - <h1>~</h6>
            - 제목 지정 태그
        - <p></p>, <br></br>
            - 줄바꿈과 띄워쓰기용 태그
        - <hr></hr>
            - 줄바꾸며 수평선 넣는 태그
        - <pre> </pre>
            - 문단 나누는 태그. 해당 태그 안에서는 별도의 기호없이 띄워쓰기와 단락 구분이 가능하다.
        - &nbsp;
            - 띄워쓰기 효과를 주는 기호
    - 글자 크기 및 효과 지정 태그
        - <strong></strong>, <b></b>
            - 글자를 굵게 표시하는 태그
        - <em></em>,<i></i>
            - 글자를 기울이는 태그
        - <blockquote></blockquote><q></q>
            - 타 사이트의 글을 인용할 경우 사용한다.
            - 자동 들여쓰기가 되어 다른 텍스트와 구별 가능하다. (blockquote)
            - (<q></q>)인용문구에 “”표시가 된다.
        - <mark></mark>
            - 형광팬 효과를 나타내는 태그
        - <u></u>
            - 글자에 밑줄을 긋는 태그
        - <small></small>
            - 글자를 작게 표시하는 태그
        - <sub></sub>, <sup></sup>
            - 순서대로 윗 첨자, 아랫첨자로 표시하는 태그
        - <s></s>
            - 글자에 취소선 삽입
        - <abbr title=””></abbr>
            - 주석 태그 “”안의 내용을 <> <>안의 내용에 주석한다.
        - <code></code>
            - <code></code> 안의 내용이 코드 문구임을 알려주는 약속
        - <kbd></kbd>
            - 키보드 입력이나 음성명령 같은 사용자 입력 내용
        - <cite><cite>
            - 참고사이트 표시할 때 사용
        
- 목록 관련 태그
    - <ul></ul>
        - 순서 없는 목록 태그
    - <ol></ol>
        - 순서 있는 목록 태그
        - 속성자로 문자, 숫자, 로마자로 순서 번호 설정 가능.
        
        ![Untitled](HTML/Untitled%2017.png)
        
    - <dl></dl>
        - 용어나 문장에 대한 정의 리스트. 설정값 디폴트로 들여쓰기가 된다.
- 표 관련 태그
    
    ![Untitled](HTML/Untitled%2018.png)
    
    - <table></table>
        - 기본적인 표를 생성해 주는 태그
        - 속성자로 <border>를 통해 표의 테두리 두께를 지정할 수 있다.
    - <tr></tr>
        - 표의 행을 나타내는 태그
    - <td></td>
        - 표의 일반 셀을 나타내는 태그
        - 속성자로 <rowspan>을 통해 지정한 행만큼 행을 병합할 수 있다.
        - 속성자로 <colspan>을 통해 지정한 열만큼 열을 병합할 수 있다.
    - <th></th>
        - 표의 제목 행을 나타내는 태그 중앙정렬 및 굵은 글씨로 표시된다.
    - <caption></caption>
        - 테이블의 제목이나 내용을 추가하는 태그.
        - 다른 태그를 이용하여 Text를 꾸밀 수 있다.
        - 기본 위치는 테이블 위 중앙.
    - <figure></figure>
        - 영역 안의 표를 구역짓는데 사용하는 태그
        - <caption>과 유사하다.
    - <figcaption></figcaption>
        - <figure>를 통해 구역지어진 영역의 테이블에 테이블의 설명, 제목 혹은 이미지의 설명과 제목에 주로 사용한다.
        figure 영역 안에서 한 번만 사용 가능하며, 어느정도 내용(테이블, 표)과 간격을 띄울 수 있다. 앞과 뒤에 대해 위치 지정도 가능하다.
    - 추가 옵션
        
        ![Untitled](HTML/Untitled%2019.png)
        
- 영역 관련 태그
    
    ![Untitled](HTML/Untitled%2020.png)
    
    - <div></div>
        - 줄 바꿈이 적용되어 이미 존재하는 태그의 다음 줄에 영역이 설정 된다. (블럭요소, 수직으로 분할)
    - <span></span>
        - 줄 바꿈이 적용되지 않아 옆으로 영역이 붙는다. (인라인 요소, 수평으로 분할)
    - <p></p>
        - 문단 영역을 지정하는 태그(블럭요소)
    - <pre></pre>
        - 입력한대로 문단 영역을 지정하는 태그(블럭 요소)
- 멀티미디어 관련 태그
    - 이미지 관련 태그
        - <img>  속성
            
            ![Untitled](HTML/Untitled%2021.png)
            
            - 상대/절대 위치 경로를 통해 이미지를 불러올 수 있다. 속성자를 통해 이미지의 크기도 지정 가능하다.
            - <img src="sample/image/flower1.PNG" width="200px" height="150px"> : 고정 크기 단위 이미지
            - <img src="sample/image/flower1.PNG" width="15%" height="150px"> : 가변 크기 단위 이미지 (%)
        - <alt> 속성
            - 이미지를 불러올 수 없을 때 대체되는 텍스트를 표시한다. (주로 화면낭독기를 위해 사용한다.)
    - 미디어 관련 태그
        - <audio></audio>
            
            ![Untitled](HTML/Untitled%2022.png)
            
            - 오디오 관련 태그:
            <audio src="sample/audio/major.mp3" autoplay="autoplay" controls="controls" loop="loop"></audio>
        - <video></video>
            - 미디어 관련 태그:
            <video src="sample/video/video1.mp4" controls="controls" ></video>
                - 최근에는 쓰이지 않는 방식. 최근에는 유투브등을  통해 a링크로 링크를 불러오는 방식이 대부분이다.
- 하이퍼링크 관련 태그
    - <a></a>
        
        ![Untitled](HTML/Untitled%2023.png)
        
        - <a> 태그를 통해 하이퍼링크 텍스트 효과를 부여할 수 있다. href 속성을 통해 연결하려는 페이지의 링크/절대(상대)경로를 입력한 뒤 <a></a>사이의 입력값을 클릭함으로서 링크로 연결 가능하다.
        - 추가적인 속성으로 target을 통해 _blank로 새창으로 열지, _self(디폴트 값)로 현재 창에서 열지 선택 가능하다.
        - id=””를 통해 “”안의 값을 href의 링크 안에 #을 통해 호출함으로서 페이지 내의 특정 장소로 이동할 수 있다.
        <a href="#content1"> 본문1</a>
        <h4 id="content1">Content1</h4>
- 폼 관련 태그
    
    ![Untitled](HTML/Untitled%2024.png)
    
    - form 관련 속성과 정의
        - form 태그는 html에서 사용자가 입력할 수 있는 양식을 제공하는 태그이다.
        - form 태그 내의 input 태그들을 통해 사용자가 입력한 정보를 서버로 넘기는 역할을 한다.
        - action 속성: 폼의 입력된 값들을 전송받을 서버의 클래스명을 입력한다.
        - method 속성: get/post 방식으로 전송받식을 지정한다.
        → get: 사용자가 입력한 값이 쿼리스트링(URL)에 노출된다
        → post: 사용자가 입력한 값이 쿼리스트링에 노출되지 않는다.
        - <form action="#" method="get">
        → #이라는 값을 가진 액션 속성으로, method에 지정된 get 방식으로 값을 전달한다.
        - <fiedset><legend></legend></fieldset> : 폼 요소를 그룹으로 묶는 태그. 묶은 폼 요소에 명칭을 붙이는 태그.
    - <input>
        
        ![Untitled](HTML/Untitled%2025.png)
        
        ![Untitled](HTML/Untitled%2026.png)
        
        ![Untitled](HTML/Untitled%2027.png)
        
        ![Untitled](HTML/Untitled%2028.png)
        
        ![Untitled](HTML/Untitled%2029.png)
        
        ![Untitled](HTML/Untitled%2030.png)
        
        ![Untitled](HTML/Untitled%2031.png)
        
        ![Untitled](HTML/Untitled%2032.png)
        
        ![Untitled](HTML/Untitled%2033.png)
        
        ![Untitled](HTML/Untitled%2034.png)
        
        - <input type =”submit”> submit이라는 이름의 버튼이 나오며, 해당 버튼을 누름으로서 input값을 전달한다.
        - button 태그를 활용해서 submit 버튼을 만들 때, 단순 버튼으로 만드려면 반드시 type=”button”으로 명시해야 한다.
        - textarea를 통해 여러 줄의 텍스트를 입력받을 수 있다.
        
        <aside>
        💡 form 태그 안에 type=”file”인 input태그가 하나라도 있다면 
        <form enctype="multipart/form-data">
        를 통해 form태그의 형식을 지정해야 한다.
        
        </aside>
        

[9_폼태그관련.html](HTML/9_%ED%8F%BC%ED%83%9C%EA%B7%B8%EA%B4%80%EB%A0%A8.html)

<aside>
💡 태그와 속성자 사이에는 한 칸씩 띄어쓰기가 필요하다.

</aside>