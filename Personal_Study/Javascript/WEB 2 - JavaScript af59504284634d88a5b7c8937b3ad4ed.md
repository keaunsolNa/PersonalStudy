# WEB 2 - JavaScript

# 오리엔테이션

- 수업 소개
    - 정적이며 만들어진 그대로 움직이는 HTML과 달리, 사용자의 행동에 따라 동적으로 작동하는 것이 JavaScript.
    - JavaScript가 만들어지게 된 계기는 사용자의 행동에 따라 변화하는, 동적인 인터넷을 만들고자 하는 소망에서 시작되었다.
- 수업의 목적
    - 아래의 코드 소개
        
        ```jsx
        <input type=”button” value+”night”  onclick=”
        Doument.querySelector(`body`).style.backgroundcolor=`black`
        Doument.querySelector(`body`).style.color=`white`
        “>
        ```
        
    - JavaScript가 HTML과 달리, 무엇을 할 수 있는지.
    - 사용자가 한 행동에 맞춰 코드가(시행될 명령이) 변화하는 JavaScript의 힘.

# HTML과 JavaScript의 만남

- script 태그
    - 기본적으로 JavaScript는 HTML 위에서 동작하는 언어다.
    - <body></body> 태그 안에, <script></script> 태그를 사용함으로서 JavaScript를 시작하는 것.
    - HTML과 JavaScript의 가장 큰 차이점은 동적과 정적..
- 이벤트
    - 웹 브라우저 위에서 일어나는 일을 사건, 이벤트라고 한다.
    - 그 종류로는 onclick, onchange, onkeydown 등이 있다.
    - 속성값은 사용자의 행동이 있었을 때, 출력되는 결과값. 아래의 코드의 경우, alert(’hi)가 해당된다.
    
    ```jsx
    <input type="button" value="hi" onclick="alert('hi')">
    ```
    
- 콘솔
    - 웹 브라우저 상에서 페이지 검사 - console 메뉴를 통해 자바스크립트를 즉석으로 실행하고, 사용자의 동작에 맞춰 변화되는 코드를 확인할 수 있다.
    - 또한, 이미 만들어진 페이지를 console메뉴의 JavaScript를 통해 수정함으로서, 수정해야 할 사항을 즉석으로 쉽게 확인 가능하다.

# 데이터타입과 소개

- 문자열과 숫자
    - JavaScript의 문자열은 
    Boolean
    Null
    Undefined
    Number
    String
    Symbol
    Object
    가 있다. 본 수업에서는 Number와 String을 배운다.
    - 산술연산자 (+. -. *. /)
    - .indexof
    .style
    .toUpperCase 와 같은 다양한 method 역시 활용 가능하다.
- 변수와 대입 연산자
    - X = 1
    에서 X가 변수, =가 대입 연산자
    - var name = “egoing” 이라는 변수를 부여했을 때,
    name은 +name+를 통해 egoing이라는 변수값으로 출력할 수 있다.
- 제어할 태그 선택하기
    
    ```jsx
    document.querySelector('body')
    getElementById('#target')
    ```
    
    - 위 코드를 통해 JavaScript에서 태그를 선택하는 방법 안내
    - ()안에 들어가는 태그에 선택자 효과를 부여하는 방식으로 이루어진다.
- 프로그램, 프로그래밍, 프로그래머
    - JavaScript와 HTML은 둘 다 컴퓨터 언어지만, HTML과 달리 JavaScript는 컴퓨터 언어인 동시에, 컴퓨터 프로그래밍 언어이기도 하다.
    - 프로그래밍은 순서를 만든다는 것.
    - 프로그래밍이란 곧 시간의 순서에 따라서 실행되어야 할 기능을 프로그래밍 언어의 문법에 맞춰 글로 적어두는 것이라고 할 수 있다.
    - JavaScript와 HTML의 가장 큰 차이는 시간의 순서가 필요한가라고 볼 수 있다.

# 조건문

- 조건문 예고
    - 조건문이란 하나의 프로그램이 하나의 흐름으로 가는 것이 아니라, 조건에 따라 다른 순서의 기능들이 실행되게 하는 것이다.
    - 토글은 조건에 따라 기능이 달라지는 버튼(스위치 등)
    - 앞으로 배워야 할 if와 boolean등의 연산자 소개.
- 비교 연산자와 Boolean
    - 비교연산자란 좌항과 우항을 결합하여 어떠한 데이터를 만드는 연산자이다.
    - 비교 연산자의 결과값은 true와 false로만 가능한데, 이 true와 false를 boolean이라고 한다.
    
    ```jsx
    1&lt;1
    1&gt;1
    ```
    
    - 각각 ≥ 와 ≤
- 조건문
    
    ```jsx
    if() {
    } else {
    }
    ```
    
    - ()안의 boolean 값이 true라면 첫 번째 { }를, false라면 두 번째 { }를 실행하는 if 조건문의 기본적인 형식
- 조건문의 활용
    - 앞서 배운 조건문을 활용하여 직접 코드를 작성해본다. 그 코드는 아래와 같다.
    
    ```jsx
    <input id="night_day" type="button" value="night" onclick="
        if(document.querySelector('#night_day').value === 'night'){
          document.querySelector('body').style.backgroundColor ='black';
          document.querySelector('body').style.color='white';
          document.querySelector('#night_day').value = 'day';
        } else {
          document.querySelector('body').style.backgroundColor ='white';
          document.querySelector('body').style.color='black';
          document.querySelector('#night_day').value = 'night';
        }
        ">
    ```
    
    - 위 코드의 경우, 하나의 “night_day” 라는 id의  “button” 을 눌렀을 때. value값이 “night” 였는지, “day”였는지에 따라 서로 다른 결과값을 출력하게 만들 수 있다.
- 리팩토링 중복의 제거
    - 리팩토링이란 재공정, 비효율적인 코드와 중복의 제거를 통해 코드를 개선하는 것을 뜻한다.
    - 위 조건문에서 살펴봤던 코드의 경우, document.querySelector('#night_day')
    밑의 document.querySelector('body') 
    코드와 달리 자기 자신(input 태그)를 가리키고 있다.
    - document.querySelector('body') 코드 역시
    var target = document.querySelector('body'); 로 지정함으로서, target으로 대체가 가능하다.
    - 위와 같은 방식을 이용했을 경우, 위의 코드는 아래와 같이 리팩토링이 가능하다.
    
    ```jsx
    <input id="night_day" type="button" value="night" onclick="
    var target = document.querySelector('body');
        if(this.value === 'night'){
          target.style.backgroundColor ='black';
          target.style.color='white';
          document.querySelector('#night_day').value = 'day';
        } else {
          target.style.backgroundColor ='white';
          target.style.color='black';
          this.value = 'night';
        }
        ">
    ```
    

# 반복문

- 반복문 예고
    - 앞으로 배울 배열과 반복문에 대한 간략한 소개 영상
    - 반복문은 조건문과 달리, 일정한 순서로 진행되는 프로그램 언어에서 특정 구간을 반복하게 만드는 것.
- 배열
    - 배열의 필요성은 반복문을 배우는 과정에서 알 수 있다.
    - 프로그래밍을 하며 방대해지는 데이터를, 연관된 데이터를 기준으로 묶어 정리하는 것이 바로 배열
    - 배열은 []로 시작해서 []로 끝난다.
    - 배열 안에 있는 값(인덱스)와 값 사이는 콤마(,)로 구분한다.
    
    ```jsx
    array3.push('1')
    array3.length
    ```
    
    - push는 배열 안에 인덱스를 추가할 때 사용되며
    - length 는 배열의 길이를 계산할 때 사용된다.
- 반복문
    - 반복문 역시 조건문과 함께 제어문의 일종이다.
    
    ```jsx
    var i = 0;
    while(i<3){
    document.write('2');
    i = i+1;
    }
    ```
    
    - 위 코드를 출력 시, i가 3보다 작아질 때까지, 2가 3번 출력된다.
- 배열과 반복문
    - 앞서 배운 배열과 반복문을 활용하여, 반복문에 배열을 추가하여 특정 조건 하에서 반복되는 코드를 작성한다.
    
    ```jsx
    var array = ['1','2','3','4','5'];
    var i = 0;
    while(i<array.length){}
    ```
    
    - 위 코드의 경우, i 변수가 배열의 길이인 5보다 같거나 커질 때 까지 {}안의 내용을 출력하는 반복문이 된다.
- 배열과 반복문의 활용
    - Document.querySelectorAll(’a)
    이 문서에 있는 ‘a’ 태그를 모두 가져오는 태그.
    - 앞서 배웠던 내용들을 기반으로, 반복문을 배열의 길이만큼 반복하는 태그의 장점을 확인한다.

# 함수

- 함수 예고
    - 함수(function) 은 메소드(method)라고도 한다.
    - 함수의 사용처는 수납상자와 유사하다.
    - 코드가 복잡해지는 과정에서, 함수의 단축을 극단적으로 가능할 수 있도록 도와주는 것이 바로 함수.
- 함수
    - 코드의 내용에 연속성이 없어 반복문을 사용할 수 없을 때, 함수의 사용처가 생긴다.
    - function name(){}
    {}안의 내용을 name이라는 이름의 함수로 규정한다.
    - 이후, name(); 을 사용해 {}안의 내용을 불러올 수 있다.
- Parameter와 Argument
    - 각각 매개변수와 전달인자이며, 둘 다 입력값을 담당한다.
    - 출력은 return.
    
    ```jsx
    <script>
    	function onePlusOne(){
    	document.write(1+1+'<br>');
    }
    onePlusOne();
    	function sum(left, right){
    	document.write(left + right + '<br>');
    }
    	sum(2,3);
    	sum(3,4)
    </script>
    ```
    
    - 위 코드에서, sum(2,3)에서 2와 3이 Parameter, 매개변수이며
    - document.write(left+right)의 left와 right가 Argument, 전달인자이다.
    - 이 때, parameter, 매개변수에서 던진 2와 3을 매개변수 left와 right에 대입, onePlusOne 함수에 의해 left + right, 2 + 3으로 정의. 5가 된다. 마찬가지로, 또 한 번 던져진 매개변수 3과 4에 의해 7이 출력되는것. 따라서 출력값은 5와 7이 된다.
- return
    - 함수는 입력인 Parameter와 Argument. 출력인 return으로 이루어져 있다.
    
    ```jsx
    <script> 
    
          function sum2(left, right){ 
    
            return left+right; 
    
          } 
    
          document.write(sum2(2,3)+'<br>'); 
    
          document.write('<div style="color:red">'+sum2(2,3)+'</div>'); 
    
          document.write('<div style="font-size:30px;">'+sum2(2,3)+'</div>'); 
    
        </script>
    ```
    
    - 위의 코드의 경우, 매개변수 left, right를  받은 sum2 함수의 리턴값이 left + right로 정의된다는 것이 return의 핵심.
    - 따라서, 출력값인 return은 아래의 sum2(2,3)에서 전달인자 2,3을 받아 parameter인 left, right가 2와 3으로 정의. 리턴값은 2+3인 5가 되는 것.
- 함수의 활용
    
    ```jsx
    <!DOCTYPE html>
    <html>
      <head>
        <meta charset="utf-8">
        <script>
        function nightDayHandler(self){
            var target = document.querySelector('body');
            if(self.value === 'night'){
              target.style.backgroundColor ='black';
              target.style.color='white';
              self.value = 'day';
    
              var alist = document.querySelectorAll('a');
              var i = 0;
              while(i < alist.length){
            alist[i].style.color = 'powderblue';
            i = i + 1;
        }
            } else {
              target.style.backgroundColor ='white';
              target.style.color='black';
              self.value = 'night';
    
              var alist = document.querySelectorAll('a');
              var i = 0;
              while(i < alist.length){
            alist[i].style.color = 'blue';
            i = i + 1;
            }
          }
        }
        </script>
        <title>
          WEB1 - Javascript
        </title>
      </head>
      <body>
        <h1><a href="index.html">WEB</a></h1>
    
        <input type="button" value="night" onclick="
        nightDayHandler(this);
        ">
        <ol>
          <li><a href="1.html">HTML</a></li>
          <li><a href="2.html">CSS</a></li>
          <li><a href="3.html">Javascript</a></li>
        </ol>
        <h2>Javascript</h2>
        <p>
          Javascript is blabla
        </p>
        <input type="button" value="night" onclick="
        nightDayHandler(this);
        ">
      </body>
    </html>
    ```
    
    - 위 코드 작성.
    - 아래의 
    <input type="button" value="night" onclick="
    nightDayHandler(this);
    ">
    코드를 통해 nightDayHandler는 this라는 Argument 값을 가진다.
    - 이후, 
    function nightDayHandler(self)
    를 통해 this의 parameter를 nightDayHandler 함수로 정의한다.
    - 따라서, 함수의 method를 self라는 명령어로 변경, 코드의 축약을 이루어낼 수 있다.

# 객체

- 객체 예고
    - 서로 연관된 함수와 연관된 변수를 같은 이름으로 그룹핑해서 정리정돈하기 위한 도구가 곧 객체.
    - 객체는 폴더와 같은 역할을 한다.
    - 정리정돈의 수단으로서의 객체를 중심으로 학습한다.
- 객체 쓰기와 읽기
    - 배열은 정보를 담는 그릇이면서, 그 정보가 순서대로 정리된다.
    - 단, 객체는 순서 없이 정리된다.
    
    ```jsx
    var varname = {}
    ```
    
    - 객체 부여 함수.
    - {}안의 코드에 varname이란 이름의 함수를 부여한다.
    - 이 때, {} 안의 코드는 “XX” : “YY”의 형식을 가지고 있어야 한다.
    - 이는 YY는 XX라는 이름(객체)를 가진다는 의미를 가지고 있다.
    - XX를 불러올 때는, 아래의 형식을 가진다.
    
    ```jsx
    document.wirte("XX : "+varname.xx);
    ```
    
- 객체와 반복문
    
    ```jsx
    for(var key in objectname) { }
    ```
    
    - 위 코드를 통해 객체에 있는 모든 key값들을 가져올 수 있다. (반복문)
    - key 값은 가져오고 싶은 정보에 도달할 수 있는 열쇠.
    
    ```jsx
    for(var key in cowokers) { 
    		document.wirte(key);
    }
    ```
    
    - 위 코드는 coworkers라는 이름의 객체에서  key값을 가져온다는 의미를 가지고 있다. (*출력한다)
- 객체 프로퍼티와 메소드
    - 객체 안에는 함수 역시 담을 수 있다.
    - Method : 객체에 소속된 함수
    - Property : 객체에 소속된 변수
- 객체의 활용
    
    ```jsx
    <!DOCTYPE html>
    <html>
      <head>
        <meta charset="utf-8">
        <script>
        var Links = {
          setColor:function(color){
            var alist = document.querySelectorAll('a');
        var i = 0;
        while(i < alist.length){
      alist[i].style.color = color;
      i = i + 1;
          }
        }
      }
      var Body = {
        setColor: function (color){
          document.querySelector('body').style.color = color;
        },
        setBackgroundColor:function (color){
          document.querySelector('body').style.backgroundColor =color;
        }
      }
        function nightDayHandler(self){
            var target = document.querySelector('body');
            if(self.value === 'night'){
              Body.setBackgroundColor('black');
              Body.setColor('white');
              self.value = 'day';
    
              Links.setColor('powderblue');
            } else {
              Body.setBackgroundColor('white');
              Body.setColor('black');
              self.value = 'night';
    
              Links.setColor('blue');
          }
        }
        </script>
        <title>
          WEB1 - Javascript
        </title>
      </head>
      <body>
        <h1><a href="index.html">WEB</a></h1>
    
        <input id="night_day" type="button" value="night" onclick="
        nightDayHandler(this);
        ">
        <ol>
          <li><a href="1.html">HTML</a></li>
          <li><a href="2.html">CSS</a></li>
          <li><a href="3.html">Javascript</a></li>
        </ol>
        <h2>Javascript</h2>
        <p>
          Javascript is blabla
        </p>
      </body>
    </html>
    ```
    
    - 지금까지 배웠던 내용들을 기반으로 위 코드 작성.  var, function을 중점으로 코드의 최적화 시도.

# 기타

- 파일로 쪼개서 정리 정돈하기
    - 함수와 객체보다 상위 개념의 정리 정돈, 그룹핑 도구로서의 파일.
    - scr = “” 명령어를 통해 외부 파일의 javascript 코드를 불러올 수 있다.
    
    ```jsx
    <script src =""> </script>
    ```
    
    - 위와 같은 방식으로 사용한다.
- 라이브러리와 프레임워크
    - 라이브러리와 프레임워크 모두 다른 사람과 협력하는 모델이라고 볼 수 있다.
    - 그 중 jQuery 라는 Javascript의 대표적인 라이브러리 소개
- UI VS API
    - User Interface와
    Application Programming Interface
    - 유저가 사용하는 인터페이스와 그 인터페이스가 작동하기 위한 프로그래밍.
    - UI 와 API의 개념 소개.
- 수업을 마치며
    - 수업을 마치며, 추후 공부할만한 분야와 검색하기 좋은 단어들 소개
    document : 어떤 태그에 자식 태그를 추가하고 싶을 때
    
    DOM(document Object Model) 다큐먼트가 돔의 일부기에, 다큐먼트로 검색이 안된다면 확장하여 검색할 것. 
    
    Windoew : 웹 브라우저 자체를 제어해야할 때
    
    ajax : 웹 페이지를 리로드하지 않고 정보를 변경하고 싶을 때
    
    cookie : 웹 페이지가 리로드 해도 현재상태를 유지하고 싶을 때
    
    offline web application : 인터넷이 끊겨도 동작하는 웹페이지를 만들고 싶을 때