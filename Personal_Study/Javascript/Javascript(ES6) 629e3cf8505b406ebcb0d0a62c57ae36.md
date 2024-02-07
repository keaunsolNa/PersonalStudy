# Javascript(ES6)

<aside>
💡 JS는 프로토타입 기반 객체 지향 언어다.

</aside>

# Intro

- History of JS
    - JavaScript6(ECMAScript 2015)가 웹 브라우저 인터프리터 언어의 표준이다. 
    → 컴파일 언어는 하나의 파일을 한꺼번에 모두 해석해서 파일로 만드는 방식이기에 상대적으로 속도와 리소스의 소모가 높다. 
    → 반면 인터프리터 언어는 하나, 한 줄 코드를 바로바로 해석해서 작동한다. 따라서 컴파일 언어에 비해 속도와 리소스의 소모가 낮다.
    - Javascript의 성장은 Ajax, jQuery, V8 Javascript 엔진, Node.js, SPA 프레임워크 등의 라이브러리를 통해 이루어졌다.
    - Node.js는 브라우저 환경에서만 동작하던 V8 엔진을 브라우저에서 독립시킨 자바스크립트 런타임 환경이다.
    - SPA 프레임워크는
    - 화면을 만드는 방식은 클라이언트의 PC에서 만드는 SPA(Single Page Application) 방식, 백엔드에서 만들어 제공하는 SSR(Server Side Rendering) 방식이 있다.
    - SPA는 가상 돔을 통해 페이지의 Component를 변경하는 방식, SSR은 백단에서 페이지를 통쨰로 만들어 제공, 변경이 있을 때 새로운 페이지를 만들어 제작하는 방식이다.
- Characteristic of ****JS
    - 웹 브라우저에서 동작하는 유일한 프로그래밍 언어, 인터프리터 언어이다.
    - 대부분의 모던 JS 엔진은 인터프리터와 컴파일러의 장점을 결합해 비교적 처리 속도가 느린 인터프리터의 단점을 해결했다.
    - 클래스 기반 객체지향 언어보다 효율적이면서 강력한 프로토타입 기반의 객체지향 언어이다.
    → 자바는 클래스를 통해 객체들을 생성한다.
    → JS는 클래스를 만들면 prototype을 만들고, 객체를 만들 때 prototype을 통해 객체를 찍어낸다.
- Node.js Installation
    - 공식 페이지 접속
        
        [Node.js](https://nodejs.org/en/)
        
    - 설치파일(LTS 버전) 다운로드 후 Next 눌러 설치 완료
    - 설치 완료 후 cmd 창에서 node -v, npm -v 명령어 입력, 버전 확인
    - 이후 Visual Studio에서 JS 작성 시 컨트롤 + 쉬프트 + `로 터미널 창 진입 가능
    - 해당 콘솔 창에서 컨트롤 + 탭 키로 작성된 JS 파일 확인 가능. 시행 시 해당 JS 파일의 실행 가능.
    - Code Runner 설치 후에는 컨트롤 + 알트 + N 키로 해당 JS 파일 실행 가능

# Data-type

- Intro
    - data-type은 값의 종류를 말하며 자바스크립트(ES6)는 7개의 데이터 타입
        (number, string, boolean, undefined, null, symbol, object)를 제공한다.
    - JS는 정적 타입인 자바와는 달리 동적 타입으로 변수의 자료형을 지정한다. 
    → 대입을 하면 자료형이 지정된다.
- Variable
    - Primitive Type
        - number
            - JS는 정수, 실수, 음수 모두 숫자(number) 타입이다.
            - 단, 내부적으로는 실수로 인식한다.
            - 숫자 타입은 추가적으로 Infinity, -Infinity, NaN이 있다. 
            → 양의 무한대, 음의 무한대, 산술 연산 불가
            
        - string
            - 일반 문자열 안에서는 개행문자가 허용되지 않는다.
            - 단, 백틱(`)을 사용하면 줄바꿈이 허용되고, 모든 공백이 있는 그대로 적용된다.
            - 문자열 이어붙이기는 JS에서도 + 연산자를 활용하여 가능하다.
            - 혹은, 표현식 삽입(${})과 백틱을 함께 사용해도 가능하다.
                
                console.log(`제 이름은 ${lastName}${firstName}입니다.`);
                
            - 단, 백틱이 아닌 일반 문자열을 사용시 표현식 삽입은 문자열로 취급된다.
        - boolean
            - boolean 타입의 값은 논리적 참, 거짓을 나타내는 true와 false 뿐이다.
        - undefined-and-null
            - JS는 동적 타입이기에 대입이 이루어지지 않으면 변수의 타입이 없다. 따라서 undefined가 지정된다.
            - var 키워드로 선언한 변수는 암묵적으로 undefined로 초기화 되므로 변수를 선언한 이후 값을 할당하지 않은 변수는 undefined를 지니고 있다. 개발자가 의도적으로 변수에 할당하는 것은 본래 취지와 어긋나고 혼란을 줄 수 있으므로 지양한다.
            - null타입은 변수에 값이 없다는 것을 의도적으로 명시할 때 사용한다. 
            → 이후 JS의 GC가 해당 값을 지운다.
            - 즉, undefined는 타입이 아예 지정되지 않았고, null은 타입은 지정되었으나 값이 없다는 의미다.
        - symbol-and-object
            - symbol은 ES6에서 추가 된 7번째 타입으로 변경 불가능한 원시 타입의 값이다.
            - 다른 값과 중복 되지 않는 유일무이한 값으로 이름이 충돌할 위험이 없는 객체의 유일한 프로퍼티 키를 만들기 위해 사용한다.
            - symbol 이외의 원시 값은 리터럴을 통해 생성하지만 symbol은 Symbol 함수를 통해 호출해 생성한다.
                
                ```
                var key1 = 'key';
                var key2 = 'key';
                console.log(typeof key1);
                console.log(typeof key2);
                ```
                
            - 리터럴 객체는 객체를 만들 때 속성을 지정하지 않아도 호출할 때 속성을 지정하면 속성을 만든다.  (Property Key, Property Value)
                
                ```
                var obj = {};
                
                obj[key1] = 'value1';       // In Java = class obj{ String key1; String key2; } new obj();
                obj[key2] = 'value2';
                console.log(obj[key1]);
                console.log(obj[key2]);
                ```
                
            - 위 코드의 경우, key1과 key2는 ‘key’라는 string 타입의 속성 하나만 가지고 있기에, 두 console.log는 동일한 value2가 출력된다.
                
                ```
                var key1 = Symbol('key');
                var key2 = Symbol('key');
                console.log(typeof key1);
                console.log(typeof key2);
                ```
                
            - 위 선언부를 Symbol로 변경하면 문자열을 지니고 있는 Symbol Type이 되기에 동등객체지만 동일 객체가 아니다. 따라서 console.log()는 value1과 value2를 출력한다.
            - 결국, Symbol 타입은 객체를 만들 때마다 다른 타입으로 받아들인다는 것이 핵심이다.
            - Symbol 타입은 이름 충돌의 위험이 있을 때 유일무일한 값인 symbol을 프로퍼티 키(속성명) 으로 사용한다.
            - Object, 객체 타입은 number, string, boolean, undefined, null, symbol의 6가지 객체 타입 이외는 모두 객체 타입이다.
            - 그 종류는 객체, 함수, 배열 등이 있다.
            
    - Dynamically-typed-language
        - 정적 타입(static/strong type) 언어
            - : C, C++, Java, Kotlin 등
            - 변수를 선언할 때 데이터 타입을 사전에 선언(명시적 타입 선언) 해야 한다.
            - 변수의 타입을 변경할 수 없으며, 변수에 선언한 타입에 맞는 값만 할당할 수 있다.
            - 컴파일 시점에 타입 체크를 수행하는데 타입의 일관성을 가엦하여 런타임 에러를 줄인다.
        - 동적 타입(dynamic/weak type) 언어
            - : JavaScript, Python 등
            - 자바스크립트는 var, let, const 키워드를 사용해 변수를 선언할 뿐 데이터 타입을 사전에 선언하지 않는다.
            - 즉, 동적 타입 언어는 변수 선언이 아닌 할당에 의해 타입이 결정(타입 추론)되며 재할당에 의해 변수의 타입은 언제든지 동적으로 변할 수 있다.
            - 변수의 값이 언제든지 변경(타입이 변경)될 수 있기 때문에 값을 확인하기 전에는 타입을 확신할 수 없다.
            - 개발자의 의도와 상관 없이 자바스크립트 엔진에 의해 암묵적으로 타입이 자동 변환되기도 한다.
            - 즉, 유연성은 높지만 신뢰성은 떨어진다.
            - 이로 인해 변수를 사용하기 전 데이터 타입 체크를 하기도 하는데 이는 번거롭기도 하고 코드의 양도 증가한다.
                
                ```
                var test;
                console.log(typeof test);
                
                test = 1;
                console.log(typeof test);
                
                test = 'Javascript';
                console.log(typeof test);
                
                test = true;
                console.log(typeof test);
                
                test = null;
                console.log(typeof test);
                
                test = Symbol();
                console.log(typeof test);
                
                test = {};
                console.log(typeof test);
                
                test = [];
                console.log(typeof test);
                
                test = function(){};
                console.log(typeof test);
                ```
                
            - 위 코드의 경우 값은 아래와 같다.
                
                ![Untitled](Javascript(ES6)%20629e3cf8505b406ebcb0d0a62c57ae36/Untitled.png)
                
            - null타입의 경우, JS 첫번째 버전부터 있었던 버그지만 아직 고쳐지지 않은 버그. null타입이 맞다.
    - Implicit_coercion
        - Convert-to-string
            - 문자열 타입이 아닌 피연산자와 문자열 연결 연산자로 동작하면 암묵적으로 변환
                
                ```
                console.log(10 + '20');
                ```
                
            - 템플릿 리터럴 방식에서 표현식 삽입은 문자열 타입으로 암묵적으로 변환
                
                ```
                console.log(`10 + 20 : ${10 + 20}`);
                ```
                
            - 당연하지만, Symbol 타입은 변환되지 않는다. 
            → Cannot convert a Symbol value to a string Exception 발생
        - Convert-to-number
            - 더하기 연산자를 제외한 산술 연산자는 피연산자들이 숫자여야 하므로, 피연산자들을 숫자 타입으로 암묵적 형변환한다.
            - 비교 연산자 역시 크기를 비교하기 위해 모두 숫자 타입이어야 하므로 피 연산자들을 숫자 타입으로 암묵적 타입 변환한다.
                
                ![Untitled](Javascript(ES6)%20629e3cf8505b406ebcb0d0a62c57ae36/Untitled%201.png)
                
            - + 단항 연산자를 활용 숫자 타입으로 암묵적 타입 변환이 가능하다.
        - convert-to-boolean
            
            ![Untitled](Javascript(ES6)%20629e3cf8505b406ebcb0d0a62c57ae36/Untitled%202.png)
            
            ![Untitled](Javascript(ES6)%20629e3cf8505b406ebcb0d0a62c57ae36/Untitled%203.png)
            
            - 위 코드에서 처음과 마지막을 제외한 값들은 모두 Falsy 값.
            - 자바스크립트 엔진은 불리언 타입이 아닌 값을 Truthy 값(참으로 평가 되는 값) 또는 Falsy 값(거짓으로 평가되는 값)으로 구분한다. 즉,  Truthy -> true, Falsy -> false로 암묵적 타입 변환을 한다.
            - false, undefined, null, 0, NaN, ''(빈 문자열)은 Falsy 값이며 이 외의 모든 값은 Truthy 값이다.
            
    - Eexplicit-coercion
        - 명시적 타입 변환이란
            - 개발자의 의도에 따라 값의 타입을 변환하는 것이다. 자바스크립트에서 기본적으로 제공하는 표준 빌트인 생성자 함수 (String, Number, Boolean)을 new 연산자 없이 호출하는 방법, 빌트인 메소드를 사용하는 방법, 암묵적 타입 변환을 이용하는 방법이 있다.
            - 빌트인 생성자 함수 : 호출할 때 new 연산자를 별도로 호출하지 않아도 내부적으로 new 연산자를 호출하여 객체를 생성하는 함수.
        - convert-to-string
            - String 생성자 함수를 new 연산자 없이 호출
                
                ```jsx
                console.log(String(10));
                console.log(String(NaN));
                console.log(String(Infinity));
                console.log(String(true));
                console.log(String(false));
                ```
                
            - Object.prototype.toString 메서드 이용
                - Object로부터 물려 받아 toString()을 사용할 수 있다. Java와는 달리 prototype이 들어간다.
                
                ```jsx
                console.log((10).toString());
                console.log((NaN).toString());
                console.log((Infinity).toString());
                console.log((true).toString());
                console.log((false).toString());
                ```
                
        - convert-to-number
            - Number 생성자 함수 new 연산자 없이 호출
                
                ```
                console.log(Number('10'));
                console.log(Number('10.01'));
                console.log(Number(true));
                console.log(Number(false));
                ```
                
            - parseInt, parseFloat 함수 이용 (문자열 → 숫자만 가능)
                
                ```
                console.log(parseInt('10'));
                console.log(parseFloat('10'));
                console.log(parseInt('10.01'));
                console.log(parseFloat('10.01'));
                console.log(parseInt(true));              // NaN
                ```
                
            - + 단항 산술 연산자 이용
                
                ```
                console.log('10' + 1);
                console.log('10.01' + 1);
                console.log(true + 1);
                console.log(false + 0.1);
                ```
                
            - *  산술 연산자 활용
                
                ```
                console.log('10' * 1);
                console.log('10.01' * 1);
                console.log(true * 1);
                console.log(false * 0.1);
                ```
                
        - convert-to-boolean
            - Boolean 생성자 함수를 new 연산자 없이 호출하는 방법
                
                ```jsx
                console.log(Boolean('JavaScript'));     // true
                console.log(Boolean(''));               // false
                console.log(Boolean(1));                // true
                console.log(Boolean(0));                // false
                console.log(Boolean(NaN));              // false
                console.log(Boolean(Infinity));         // true
                console.log(Boolean(null));             // false
                console.log(Boolean(undefined));        // false
                console.log(Boolean({}));               // true
                console.log(Boolean([]));               // true
                ```
                
            - ! 논리 부정 연산자를 두 번 사용하는 방법
                
                ```
                console.log(!!'JavaScript');            // true
                console.log(!!'');                      // false
                console.log(!!1);                       // true
                console.log(!!0);                       // false
                console.log(!!NaN);                     // false
                console.log(!!Infinity);                // true
                console.log(!!null);                    // false
                console.log(!!undefined);               // false
                console.log(!!{});                      // true
                console.log(!![]);                      // true
                ```
                
- Operator
    - comparison-operator
        
        ```jsx
        /* 1. 숫자 1, 문자 '1', true 비교 */
        console.log(`1 == '1' : ${1 == '1'}`);                      // true
        console.log(`1 == true : ${1 == true}`);                    // true
        console.log(`1 == '1' : ${1 === '1'}`);                     // false
        console.log(`1 == true : ${1 === true}`);                   // false
        
        /* 2. 숫자 0, 문자 '0', 빈문자열 '', false 비교 */
        console.log(`0 == '0' : ${0 == '0'}`);                      // true
        console.log(`0 == '' : ${0 == '0'}`);                       // true
        console.log(`0 == false : ${0 == '0'}`);                    // true
        console.log(`0 === '0' : ${0 === '0'}`);                    // false
        console.log(`0 === '' : ${0 === ''}`);                      // false
        console.log(`0 === false : ${0 === false}`);                // false
        
        /* 3. null, undefined 비교 */
        console.log(`null == undefined : ${null == undefined}`);    // true
        console.log(`null === undefined : ${null == undefined}`);   // false
        
        /* 4. NaN은 자신과 일치하지 않는 유일한 값이다. */
        console.log(`NaN == NaN : ${NaN == NaN}`);                  // false
        console.log(`NaN === NaN : ${NaN === NaN}`);                // false
        
        console.log(`Number.isNaN(NaN) : ${Number.isNaN(NaN)}`);    // true
        
        /* 5. 일치하지 않는 값 비교 */
        console.log(`1 != '1' : ${1 != '1'}`);                      // false
        console.log(`1 !-- '1' : ${1 !== '1'}`);                    // true
        
        /* 자바스크립트는 문자열 대소 비교도 가능하다. */
        console.log(`'1234' == '3456' : ${'1234' == '3456'}`);      // false
        console.log(`'1234' == '1234' : ${'1234' == '1234'}`);      // true
        ```
        
        - 동등 비교(loose equality) 연산자와 동일(일치) 비교(strict equality) 연산자는 비교하는 엄격성의 정도가 다르다.
        - 동등 비교(==, !=) 연산자는 먼저 암묵적 타입 변환을 통해 타입을 일치시킨 후 같은 값을 지녔는지 비교한다.
        - 동일(일치) 비교(===, !==) 연산자는 타입과 값이 모두 일치하는지를 비교한다.
    - short-circuit-evaluation
        
        ```
        /* 1. OR의 경우 */
        // 왼쪽 값이 false이면 오른쪽 아니면 왼쪽
        console.log('apple' || 'banana');           // apple
        console.log(false || 'banana');             // banana
        console.log('apple' || false);              // apple
        
        /* 2. And의 경우 */
        // 왼쪽 값이 true라면 오른쪽 아니면 왼쪽
        console.log('apple' && 'banana');           // banana
        console.log(false && 'banana');             // false
        console.log('apple' && false);              // false
        ```
        
        - OR 연산자는 좌항을 기준으로 연산한다. 문자열은 truthy한 값이기에 좌항이 문자열이거나 true면 우항을 연산하지 않고 좌항을 반환한다.
        - 좌항이 false라면 우항을 반환한다.
        - AND 연산자의 경우 좌항이 true더라도 우항도 true여야 하기에 우항을 반환한다.
        - 좌항이 false라면 좌항을 반환한다.
        - short-circuit-evaluation을 활용하여 If문을 대체할 수 있다.
            
            ```
            var num = 1;
            
            if(num % 2 == 0)
                console.log('짝수입니다.');
            else 
                console.log('홀수입니다.');
            
            num % 2 == 0 && console.log('짝수입니다.');
            num % 2 == 0 || console.log('홀수입니다.');
            ```
            
        - 
- ES11 Operator
    - optional-chaining-operator
        - EC11(ECMAScript2020)에서 도입된 연산자로 좌항의 피연산자가 null 또는 undefined인 경우 undefined를 반환하고 그렇지 않으면 우항의 프로퍼티 참조를 이어간다.
            
            ```
            var obj = null;
            
            // var val = obj.value;
            // console.log(val);            // Cannot read properties of null 
            
            var val = obj?.value;           // npe 방지 코드 개념
            console.log(val);               // undefined
            
            var str = '';                   // falsy하지만 null이나 undefined가 아닌 경우
            var len = str?.length;
            console.log(len);
            ```
            
    - nullish-coalescing-operator
        - ES11(ECMAScript2020)에서 도입된 연산자로 좌항의 피연산자가 null 또는 undefined인 경우 우항의 피연산자를 반환하고, 그렇지 않으면 좌항의 피연산자를 반환한다.
        - 변수에 기본 값을 설정할 때 유용하다
            
            ```jsx
            var test = null ?? '기본 값';
            console.log(test);                  // 기본 값
            
            var value1 ='' || '기본 값';
            var value2 = '' ?? '기본 값';
            console.log(value1);                // 기본 값
            console.log(value2);                // (공백 문자열)
            ```
            

# Object-literal

- Object
    - 자바스크립트는 객체 기반 프로그래밍 언어로 원시 값을 제외 한 나머지 값(함수, 배열, 정규 표현식 등)은 모두 객체이다.
        
        ```
        var student = {
        
            /* 키-값의 쌍으로 구성된 프로퍼티 */
            /* 프로퍼티 : 객체의 상태를 나타내는 값(data) */
            name : '유관순',
            age : 16,
        
            /* 메소드: 프로퍼티(상태 데이터)를 참조하고 조작할 수 있는 동작(behavior)(기능) */
            getInfo : function(){
                return `${this.name}(은)는 ${this.age}세 입니다.`;
            }
        };
        
        console.log(student);                   // { name: '유관순', age: 16, getInfo: [Function: getInfo] }
        console.log(typeof student);            // object
        
        console.log(student.getInfo);           // [Function: getInfo]
        console.log(student.getInfo());         // 유관순(은)는 16세 입니다.
        
        student.name = '홍길동';
        student.age = '44';
        
        /* 메소드 안의 this는 메소드를 호출한 대상이다. */
        console.log(student.getInfo());         // 홍길동(은)는 44세 입니다.
        ```
        
    - 객체 리터럴의 중괄호는 코드 블록을 의미하지 않는다. 따라서 닫는 중괄호 뒤에는 세미콜론을 붙인다.
- Property
    - Property Key&Value
        - 객체는 프로퍼티의 집합이며, 프로퍼티는 키와 값으로 구성 된다.
        - 프로퍼티 키 : 빈 문자열을 포함하는 모든 문자열 또는 symbol 값 => 프로퍼티 값에 접근하기 위한 식별자 문자열이므로 홑따움표를 사용하지만 식별자 네이밍 규칙을 따르는 경우 사용하지 않아도 된다.($_) 단, 식별자 네이밍 규칙을 따르지 않는 이름은 홑따움표를 반드시 사용해야 한다.
        - 프로퍼티 값 : 자바스크립트에서 사용할 수 있는 모든 값.
            
            ```jsx
            var obj = {
                normal : 'normal value',
                '@ s p a c e @' : 'space value',
                '' : '',                                    // 빈 문자열 키 : 오류는 발생하지 않지만 권장되지 않는다.
                0 : 1,                                      // 숫자 키 : 내부적으로 문자열로 반환된다.
                var : 'var',                                // 예약어 키 : 오류는 발생하지 않지만 권장되지 않는다. 
                normal : 'new value'                        // 이미 존재하는 키를 중복 선언하면 나중에 선언한 프로퍼티가 기존 프로퍼티를 덮어쓴다.
            };
            ```
            
        - 프로퍼티 키와 값을 동적으로 추가(생성) 할 수 있다.
            
            ```
            var key = 'test';
            obj[key] = 'test value';
            ```
            
        - 프로퍼티 추가 순서는 정수 프로퍼티는 자동으로 정렬되고, 그 외의 프로퍼티는 객체에 추가한 순서 그대로 정렬된다.
    - method
        - 자바 스크립트의 함수는 객체이다. 함수는 값으로 취급할 수 있고 프로퍼티 값으로 사용할 수 있다.
            
            ```
            var dog = {
                name : '뽀삐',
                eat : function(food){
                    // console.log(`${name}(은)는 ${food}를 맛있게 먹어요.`);       // name is not defined Exception 
                    console.log(`${this.name}(은)는 ${food}를 맛있게 먹어요.`);     // this.은 생략하면 안 된다. 
            
                    return '잘 먹었네';
                }
            };
            
            console.log(dog.eat('banana'));
            ```
            
        - JS의 함수는 결국 객체기에, property인 this가 없으면 not defined Exception이 발생한다.
    - property accessor
        - dot notation
            - 마침표 표기법은 다음과 같이 사용한다.
                
                ```
                console.log(dog.name);
                dog.eat('고구마');
                ```
                
        - square braket notation
            - 대괄호 표기법 프로퍼티 키는 반드시 홑따움표로 감싼 문자열을 사용해야 한다.
                
                ```
                console.log(dog['name']);
                dog['eat']('피자');
                ```
                
            - 아래처럼 프로퍼티 키가 식별자 네이밍 규칙을 준수하지 않은 경우, 반드시 대괄호 표기법을 사용해야 한다.
                
                ```
                var obj = {
                    'dash-key' : 'dash-value',
                    0 : 1
                };
                ```
                
            - 프로퍼티 키가 숫자로 이루어진 경우 홑따움표를 생략할 수 있지만 대괄호 표기법을 사용해야 한다.
        - property change and remove
            
            ```
            var dog = {
                name : "뽀삐"
            };
            
            /* 1. 프로퍼티 수정 */
            dog.name = "두무";
            dog['name'] = '두부';
            console.log(dog);
            
            /* 2. 프로퍼티 동적 추가 */
            /* 존재하지 않는 프로퍼티에 접근해서 값을 할당하면 프로퍼티가 동적으로 생성 되어 추가되고, 프로퍼티 값이 할당 된다. */
            dog.age = 3;
            dog['age'] = 30;
            console.log(dog);
            
            /* 3. 프로퍼티 동적 삭제 */
            delete dog.age;
            delete dog['age'];
            
            delete dog.something;       // 만약 존재하지 않는 프로퍼티를 삭제해도 에러 없이 무시된다.
            console.log(dog);
            ```
            
    - propert value shorthand
        - ES6에서 추가된 개념으로, 프로퍼티 값 단축 구문이라고 한다.
        - 변수 이름과 프로퍼티 키가 동일한 이름일 때, 프로퍼티 키를 생략할 수 있다.
        - 프로퍼티 키는 변수 이름으로 자동 생성된다.
        - 아래와 같이 사용한다.
            
            ```
            var product2 = { id, price };
            console.log(product2);
            ```
            
    - computed property name
        - ES5에서 추가된 개념으로, 계산된 프로퍼티 이름이라고 한다.
        - 프로퍼티 이름을 넣을 때 계산을 할 수 있다.
        - 아래와 같이 사용한다.
            
            ```
            var prefix = 'key';
            var index = 0;
            
            var obj ={};
            
            /* 대괄호 표기법을 사용해야 한다. (ES5) */
            obj[prefix + '-' + index++] = index;
            obj[prefix + '-' + index++] = index;
            obj[prefix + '-' + index++] = index;
            
            console.log(obj);
            
            /* ES6에서는 객체 리터럴 내부에서도 계산된 이름으로 프로퍼티 키를 동적으로 생성할 수 있다. */
            var obj2 = {
                [`${prefix}-${index++}`] : index,
                [`${prefix}-${index++}`] : index,
                [`${prefix}-${index++}`] : index
            }
            
            console.log(obj2);
            ```
            
    - method shorthand
        - ES6에서는 메소드를 정의할 때 function 키워드를 생략한 축약 표현을 사용할 수 있다.
            
            ```
            var dog2 = {
                name : '두부',
                eat(food) {
                    console.log(`${this.name}(은)는 ${food}를 맛있게 먹어요.`);
                }
            };
            
            dog2.eat('고구마');
            ```
            
- Additional Operator And Traversal
    - in operator
        - 프로퍼티가 존재하는지 확인하기 위해 사용하는 연산자다.
        - 아래와 같이 사용한다.
            
            ```
            var student = {
                name : '유관순',
                age : 16,
                test : undefined
            };
            
            console.log("name" in student);             // true  - 존재
            console.log('height' in student);           // false - 존재하지 않음
            console.log("test" in student);             // true  - 존재
            ```
            
    - for in
        - in 연산자를 활용, 객체에 있는 프로퍼티 값의 존재여부를 순회할 때 자주 사용한다.
        - 사용법은 아래와 같다.
            
            ```
            var student = {
                name : '유관순',
                age : 16,
                getInfo : function(){
                    return `${this.name}(은)는 ${this.age}세입니다.`;
                }
            };
            
            for(var key in student){
            	  console.log(`key : ${key}`);                            // 키
                console.log(`student[${key}] : ${student[key]}`);       // 키에 해당하는 값
            }
            ```
            
        - 주의할 점은 대괄호 사용 키에 해당하는 값에서 []를 사용해야 한다는 점. [key]는 for in 반복문의 key property에 해당하며, student에 해당하는 key property가 아니다.
            
            

# Function

- Function Definition
    - function declaration
        - 익명 함수가 아닌 함수를 선언하는 방식(함수 선언문)은 다음의 두 가지 방식으로 이루어진다.
            
            ```
            function hello(name){
                return `${name}님 안녕하세요!`;
            }
            ```
            
            ```jsx
            var hello2 = function hell(){
                return 'hell';
            }
            ```
            
        - 이 때, 자바스크립트 엔진은 생성된 함수를 호출하기 위해 함수 이름과 동일한 식별자를 암묵적으로 생성하고, 거기에 함수 객체를 할당한다.
        - 즉, 함수는 함수 이름으로 호출하는 것이 아니라 함수 객체를 가리키는 식별자로 호출한다.
        - 따라서 두 번째 코드의 경우,
            
            ```jsx
            console.log(hello2());
            ```
            
        - 위처럼 호출 시에는 에러가 발생하지 않지만
            
            ```jsx
            console.log(hell());
            ```
            
        - 위처럼 호출 시에는 함수의 이름(hell)이 아닌 식별자(hello2)로 함수를 호출하기에 (hell) is not defined Exception이 발생한다.
    - function expression
        - 함수 표현식은 아래와 같은 방식으로 선언한다.
            
            ```
            var hello = function (name){
                return `${name}님 안녕하세요!`;
            }
            
            var calc = function add(a, b){
                return a + b;
            };
            ```
            
        - 자바스크립트의 함수는 객체 타입의 값으로 값의 성질을 갖는 객체를 일급 객체라고 한다.
        - 함수는 일급 객체이므로 함수 리터럴로 생성된 함수 객체를 변수에 할당할 수 있다.
        - 일급 객체는 다른 함수의 매개변수로 던질 수도, 함수의 반환값으로 반환할 수도, 다른 변수에 대입할 수도 있다.
    - function hoisting
        - 함수 선언문은 런타임 이전 자바스크립트 엔진에 의해 먼저 실행된다.
        - 따라서 함수 선언문 이전에 함수를 참조할 수 있으며 호출할 수도 있다.
        - 함수 선언문이 코드의 선두로 끌어 올려진 것처럼 동작하는 자바스크립트 고유의 특징을 호이스팅이라고 한다.
            
            ```
            console.log(hello('홍길동'));
            console.log(hi);
            
            /* 함수 선언문(먼저 해석함) */
            /* 함수 선언문은 반드시 함수의 이름을 명시해야 한다. */
            function hello(name){
                return `${name}님 안녕하세요!`;
            };
            
            /* 함수 표현식 */
            var hi = function(name){
                return `${name} 안녕~`;
            };
            ```
            
        - 따라서, 위의 함수 선언문은 정상적으로 콘솔에 호출되나, 함수 표현식은 undefined가 호출된다.
- Function Call
    - Parameter And Argument
        - 매개변수는 함수 블럭 내부에서만 참조할 수 있다.
        - 모든 함수는 암묵적으로 argument 객체의 프로퍼티로 보관된다.
        - 함수 호출 시 매개변수의 개수와 인수의 개수가 일치하는지 체크하지 않는다.
        - if문과 throw 를 활용하여 인수를 체크할 수 있다. 방법은 아래와 같다.
            
            ```jsx
            function hi(name = '아무개') {
            
                if(arguments.length !== 1 || typeof name !== 'string' || name.length === 0)
                    throw new TypeError('인수는 1개여야 하고 문자열 값이며 빈 문자열을 허용하지 않습니다.');
                return `${name} 안녕~`;
            
            }
            ```
            
        - 인수를 던지지 않으면 기본값을 활용할 수 있다.
    - Return
        - 반환값 이후의 코드는 실행되지 않고 무시한다.
            
            ```jsx
            function hello(name){
                return `${name}님 안녕하세요!`;
                console.log(name);              
            };
            
            console.log(hello('유관순'));      // 유관순님 안녕하세요!
            ```
            
        - 반환 값을 명시적으로 지정하지 않거나 생략하면 undefined()가 반환된다.
            
            ```jsx
            function func(){
                console.log('함수가 호출되었습니다.');
                // return;
            };
            
            console.log(func());                // undefined
            ```
            
- Arrow Function
    - Arrow Function Basic Syntax
        
        ```
        message = () => {
            return "Arrow Function!";
        };
        ```
        
        - 위와 같은 방식으로 선언한다. function 키워드를 생략할 수 있다.
        - 함수의 실행 구문이 하나만 있을 경우 중괄호도 생략할 수 있다.  또한, 함수 내부의 명령문이 값으로 평가 될 수 있는 표현식의 경우 return 값을 암묵적으로 반환, return을 생략할 수 있다.
            
            ```java
            message = () => "Arrow Function!";
            ```
            
        - 매개변수가 단 한 개만 있을 경우에는 소괄호도 생략 가능하다. 매개변수가 없거나 2개 이상일 때는 불가능하다.
            
            ```java
            message = val1 => "Arrow" + val1;
            ```
            
- Various Types Of Functions
    - Immediately Invoked Function Expression
        - 즉시 실행 함수는 window.onload() 함수처럼 문서가 실행 될 때 자동으로, 단 한 번만 실행된다. 함수의 이름을 붙일 수 있지만 함수의 이름으로 다시 호출할 수 없어 의미는 없다. 함수의 형태는 아래와 같다.
            
            ```
            (function hello(name){
                console.log('기명 즉시 실행함수! 함수 정의와 동시에 호출!');
                console.log(`${name}님 안녕하세요!`);
            })('홍길동');
            ```
            
        - 매개변수로 값을 넣고 던질 수도 있다. 즉시 실행 함수는 지역변수 개념으로 한 번만 사용되므로, 즉시 실행 함수 내에 코드를 모아두면 변수나 함수의 이름 충돌을 방지하기 용이하다.
    - Recursive Function
        - 재귀 함수는 함수가 자기 자신을 호출하는 함수를 의미한다.
        - 팩토리얼이 대표적인 재귀 함수의 활용 예제.
        - 반복 처리를 반복문 없이 구현할 수 있어 시간 복잡도에서 우수하다.
        - 방식은 아래와 같다.
            
            ```
            function factorial(n){
                if(n <= 1) return 1;
                return n * factorial(n -  1);
            }
            
            console.log(factorial(20));
            ```
            
    - Nested Function
        - 함수 내부에 정의 된 함수를 중첩 함수 또는 내부 함수라고 한다.
        - 중첩 함수를 호함하는 함수를 외부 함수라고 한다.
        - 일반적으로 중첩 함수는 자신을 포함하는 외부 함수를 돕는 헬퍼(helper) 함수의 역할을 한다.
        - 내부 함수는 외부 함수 안에서만 활용할 수 있다는 장점이 있다. 자바의 private 처럼 접근 제한자의 역할을 한다.
        - 중첩 함수는 스코프, 클로저와 연관되어 있다.
        - 함수의 선언 및 호출은 아래와 같은 방식으로 사용한다.
            
            ```
            function outer() {
                var outerVal = "외부함수";
            
                function inner() {
                    var innerVal = "내부함수";
            
                    console.log(outerVal, innerVal);
                }
            
                inner();
            }
            
            outer();
            ```
            
    - Callback Function
        - 함수의 매개변수를 통해 다른 함수의 내부로 전달되는 함수를 콜백 함수라고 한다
        - 매개변수를 통해 함수의 외부에서 콜백 함수를 전달 받은 함수를 고차 함수라고 한다.
        - 콜백 함수는 고차 함수에 전달되어 헬퍼 함수의 역할을 한다.
        - 기본적인 함수의 선언 및 호출은 아래와 같다.
            
            ```
            /* 전달 받은 값을 1 증가 시켜주는 함수 */
            function increase(value){
                return value + 1;
            }
            
            /* 전달 받은 값을 1 감소 시켜주는 함수 */
            function decrease(value){
                return value - 1;
            }
            
            /* 전달 받은 함수에 전달 받은 값을 적용 시켜주는 고차 함수 */
            function apply(func, value){
                return func(value);
            }
            
            console.log(apply(increase, 5));                // increase는 콜백 함수가 된다.
            console.log(apply(decrease, 5));                // decrease는 콜백 함수가 된다.
            ```
            
        - 콜백 함수는 비동기 처리(이벤트, 타이머, ajax)에 활용되는 중요한 패턴이다.
        - 콜백 함수는 위와 같은 방식보다는, 익명함수를 활용하여 사용하는 방식이 주로 활용된다. 그 방식은 아래와 같다.
            
            ```java
            console.log(apply(function(value){return value * 2;}, 5));
            
            /* 배열의 정렬을 다룰 때의 예시 */
            console.log([3, 2, 1, 5, 4].sort(function(left, right){return right - left;}));
            ```
            
    - Pure And Impure Function
        - 순수 함수 : 외부 상태에 의존하지도 않고 변경하지도 않는 함수(전달인자가 같으면 항상 같은 값을 반환하는 함수이기도 함)
        - 비순수 함수 : 외부 상태에 의존하거나 외부 상태를 변경하는 함수
        - 순수 함수는 외부 상태에 의존하지 않기에 항상 같은 값을 반환한다. 선언 및 호출은 아래와 같다.
            
            ```
            var cnt = 0;
            
            /* 순수 함수 */
            function increase(n){
                return ++n;
            }
            
            console.log(increase(cnt));             // 1
            console.log(cnt);                       // 0
            ```
            
        - 비순수 함수는 외부 상태의 영향을 받아 상태 변화를 추적하기 어려워 진다. 즉, 실행할 때마다 결과치 예측이 달라진다.
            
            ```
            /* 비순수 함수 */
            function decrease(){
                return --cnt;
            }
            
            console.log(cnt);                       // 0
            console.log(decrease(cnt));             // -1
            console.log(cnt);                       // -1
            ```
            
        - 함수 외부 상태의 변경에 영향을 주고 받지 않는(지양하는) 순수 함수를 사용하는 것이 좋다. 이는 예측이 가능하고 수정이 용이하기 때문이다.
- First Class Object
    - 일급 객체는 다음의 특징을 만족한다.
    - 1. 무명의 리터럴로 생성할 수 있다. 즉, 런타임에 생성이 가능하다.
    - 2. 변수나 자료구조(객체, 배열 등)에 저장할 수 있다.
    - 3. 함수의 매개변수에 전달할 수 있다.
    - 4. 함수의 반환값으로 사용할 수 있다.
        
        ```
        var hello = function(){
            return "안녕하세요!";
        };
        
        var obj = {hello};
        
        function repeat(func, count){
            for(var i = 0; i < count; i++){
                console.log(func());
            }
        
        	  /* 4번 만족 */
            return function(){
                console.log(`${count}번 반복 완료`);
            }
        }
        
        var returnFunc = repeat(obj.hello, 5);
        returnFunc();
        ```
        
    - 위 코드의 경우, 1번, 2번, 3번, 4번을 모두 만족하는 일급 객체다.

# Scope

- Global And Local Scope
    - 전역 스코프 <= outer 지역 스코프 <= inner 지역 스코프
    - 모든 스코프는 하나의 계층적 구조로 연결되며, 모든 지역 스코프의 최상위 스코프는 전역 스코프이다.
    - 변수를 참조할 때 자바스크립트 엔진은 스코프 체인을 통해 변수를 참조하는 코드의 스코프에서 시작하여 상위 스코프 방향으로 이동하며 선언된 변수를 검색한다.
    - 따라서 상위 스코프에서 유효한 변수는 하위 스코프에서 자유롭게 참조할 수 있지만 하위 스코프에서 유효한 변수를 상위 스코프에서는 참조할 수 없다.
        
        ```jsx
        var x = 'global x';
        var y = 'global y';
        
        function outer(){
            var z = "outer's local z";
        
            console.log(x);                         // global x
            console.log(y);                         // global y
            console.log(z);                         // outer's local z
        
            function inner(){
                var x = "inner's local x";
        
                console.log(x);                     // inner's local x
                console.log(y);                     // global x
                console.log(z);                     // outer's local z
        
            }
        
            inner();
        
        }
        
        outer();
        ```
        
- Function Level Scope
    - C, 자바 등 대부분의 프로그래밍 언어는 함수 몸체만이 아니라 모든 코브 블록(if, for, while, try/catch 등)이 지역 스코프를 만드는 블록 레벨 스코프(block level scope)를 가진다.
    - 하지만 var 키워드로 선언 된 변수는 오로지 함수의 코드 블록(함수 몸체)만을 지역 스코프로 인정하는 함수 레벨 스코프(function level scope)를 가진다.
        
        ```
        var i = 0;
        
        for(var i = 0; i < 10; i++){}
        
        console.log(i);                         // 10
        ```
        
- Let And Const
    - Var
        
        ```jsx
        /* 1. 변수 중복 선언 허용 */
        var msg = "안녕하세요.";
        console.log(msg);                               // 안녕하세요
        
        /* var 키워드가 없는 것처럼 동작한다. */
        var msg = "안녕히 가세요.";
        console.log(msg);                               // 안녕히 가세요.
        
        /* 초기화 문이 없는 중복 변수 선언은 무시된다. */
        var msg;
        console.log(msg);                               // 안녕히 가세요.
        
        /* 2. 함수 레벨 스코프 */
        var i = 0;
        for(var i = 0; i < 10; i++){}                   // 함수 내부가 아니면 전역 변수와 차이가 없는 스코프이다.
        console.log(i);
        ```
        
        - var 키워드로 변수를 선언하면 변수 호이스팅에 의해 선언문이 스코프의 선두로 끌어올려진 것처럼 동작한다. 즉, 변수 선언문 이전에 참조할 수 있다.
        - 실행 시 오류가 발생하지는 않지만 이는 프로그램의 흐름에 맞지 않고 가독성을 떨어뜨리며 오류를 만들 여지가 있다.
            
            ```jsx
            console.log(test);                              // undefined
            var test = "반갑습니다."
            ```
            
    - Let
        
        ```
        /* 1. 변수 중복 선언 금지 */
        let msg = "안녕하세요";
        
        // let msg = "안녕히 가세요";                   // 에러 발생
        
        /* 2. 블록 레벨 스코프 */
        let i = 0;
        for(let i = 0; i < 10; i++){
            console.log(`지역 변수 i : ${i}` )
        }
        confirm.log(`전역 변수 i : ${i}`)
        
        console.log(x);
        let x;                                          // Cannot access 'x' before initialization
        ```
        
        - let 키워드로 선언한 변수는 변수 호이스팅이 발생하지 않는 것처럼 동작한다.
        - let 키워드는 선언 단계와 초기화 단계를 분리하여 선언은 인지하였지만 초기화가 되지 않게 해서 오류를 발생 시킨다.
        
    - Const
        - let 키워드와 마찬가지로 블록 레벨 스코프를 가지며 중복 선언 방지 및 변수 호이스팅을 발생하지 않는 것처럼 동작한다.
            
            ```
            /* 상수는 선언과 동시에 초기화 해 주어야 한다. */
            // const x;                        // Missing initializer in const declaration
            const x = 1;
            
            // x = 2;                          // Assignment to constant variable.(값 재할당 금지(상수))
            ```
            
        - 일반적으로 함수의 이름은 대문자로 선언해서 상수임을 명확히 하며 여러 단어로 이루어진 경우 언더스코어(_)를 활용한 스네이크 케이스로 표현하는 것이 일반적이다.
            
            ```
            const DISCOUNT_RATE = 0.15;
            
            let price = 15000;                                  // 정가
            
            let discountPrice = price * (1 - DISCOUNT_RATE);    // 할인율이 적용 된 할인가
            console.log(discountPrice);
            ```
            
        - ES6 이후라면 var 키워드를 사용하지 않는다.
        - 재할당이 필요한 경우에 한정해 let 키워드를 사용하며 변경이 발생하지 않고 읽기 전용으로 사용하는 원시 값과 객체에는 const 키워드를 사용한다.
        - const 키워드는 재활용을 금지하므로 var, let 키워드보다 안전하다.
        - 전역 변수의 문제점
        - 1. 모든 코드가 전역 변수를 참조하고 변경할 수 있게 되면 가독성은 나빠지고 의도치 않게 상태가 변경될 수 있는 위험성도 높아진다.
        - 2. 전역 변수는 생명주기가 길다. 메모리 리소스도 오래 소비하며 상태 변경이 가능한 시간과 기회가 많다.
        - 3. 스코프 체인 상에서 종점에 존재하므로 전역 변수의 검색 속도가 가장 느리다.
        - => 변수의 스코프는 좁을수록 좋다.

# Object Constructor

- Object Constructor Function
    - new 연산자와 함께 Object 생성자 함수를 호출하면 빈 객체를 생성하여 반환한다.
    - 이후 프로퍼티(메소드 포함)들을 추가하여 객체를 완성할 수 있다.
        
        ```
        const student = new Object();
        // const student = {};
        console.log(Object);                    // [Function: Object], Object()도 함수임을 확인
        
        student.name = '유관순';
        student.age = 16;
        
        console.log(student);                   // { name: '유관순', age: 16 }
        console.log(student.name);              // 유관순
        ```
        
    - 반드시 Object 생성자 함수를 사용해 객체를 생성할 필요는 없다. 경우에 따라 객체 리터럴을 사용하는 것이 더 간편하다.
    
- Constructor Function
    - Constructor Function
        - 객체 리터럴 방식의 객체 생성 방식은 직관적이고 간편하지만, 단 하나의 객체만 생성한다. 동일 프로퍼티를 가지는 여러 객체를 생성하는 것에 적합하지 않다.
        - 객체를 생성하기 위한 프로퍼티들을 하나의 탬플릿 개념으로 생성자 함수로써 작성하면 동일한 프로퍼티를 가지는 여러 객체를 쉽게 생성할 수 있다.
            
            ```jsx
            function Student(name, age){
            
                /* 이 생성자 함수를 통해 생성 될 객체가 this다. */
                this.name = name;
                this.age = age;
                this.getInfo = function(){
                    return `${this.name}(은)는 ${this.age}세입니다.`;
                }
            }
            
            const student3 = new Student('장보고', 36);
            const student4 = new Student("신사임당", 40);
            ```
            
    - Instance Creation Process
        
        ```
        function  Student(name, age){
        
            /* 1. 암묵적으로 인스턴스가 생성되고 this에 바인딩 되는 과정이 런타임 이전에 실행된다. */
            console.log(this);                                  
        
            /* 2. this에 바인딩 되어 있는 인스턴스를 초기화 한다. */
            this.name = name;
            this.age = age;
            this.getInfo = function(){
                return `${this.name}(은)는 ${this.age}세입니다.`;
            }
        
            /* 3. 완성된 인스턴스가 바인딩 된 this가 return 구문을 안 적으면 암묵적으로 반환된다. */
            // return this;
        
            /* 명시적으로 객체를 반환하면 암묵적인 this 변환이 무시된다. */
            // return {};
        
            /* 생성자 함수에서는 객체 타입이 아닌 원시 값을 반환하면 무시되고 암묵적으로 this가 반환된다. */
            return 1;
        
            /* 생성자 내부에서 return은 생략하는 것이 일반적이다. */
        }
        
        const student = new Student('홍길동', 20);
        console.log(student);
        ```
        
    - Differences From Regular Function
        - 일반 함수와 생성자 함수의 특별한 형식적 차이는 없다. (첫 문자를 대문자로 기술하여 구별하는 노력 정도)
        - new 연산자 없이 호출하면 일반 함수로 동작한다.
            
            ```
            function Student(name, age){
                this.name = name;
                this.age = age;
                this.getInfo = function(){
                    return `${this.name}(은)는 ${this.age}세입니다.`;
                }
            }
            
            console.log(this);                                  // {}
            /* 
            
            */
            const student = Student("강감찬", 35);
            console.log(student);                               // undefined
            console.log(age);                                   // 35
            ```
            
        - 생성자 함수가 new 연산자 없이 호출 되는 것을 방지하기 위해 ES6에서는 new.target을 지원한다. (빌트 인 생성자 함수)
        - new.target은 new 연산자와 같이 생성자 함수가 호출 될 시 함수 자신을 가리키고 new 연산자를 사용하지 않고 생성자 함수 호출 시 undefined이다.
            
            ```jsx
            function Dog(name, age){                            // undefined -> [Function: Dog]
                console.log(new.target);
                if(!new.target){                                // new 없이 함수 호출 시 true가 되게 하는 구문
                    return new Dog(name, age);                  // new 연산자와 함께 생성자 함수를 재귀 호출하여 생성 된 인스턴스를 반환한다.
                }
            
                this.name = name;
                this.age = age;
            }
            
            const dog = Dog("뽀삐", 3);
            console.log(dog);                                   // Dog { name: '뽀삐', age: 3 }
            ```
            
        - new 연산자를 사용하지 않아도 일반함수가 아닌 객체를 생성하게 하는 생성자 함수(빌트인 생성자 함수)를 만들 수 있다.
        - 제공되는 빌트인 함수들 : Object, String, Number, Boolean, Date, RegExp, ...)

# Prototype

- Inheritance
    - [[Prototype]]
        
        ![Untitled](Javascript(ES6)%20629e3cf8505b406ebcb0d0a62c57ae36/Untitled%204.png)
        
        - JS의 모든 객체는 프로토타입 객체로부터 만들어지며, 모든 객체는 프로토타입 객체에 접근할 수 있다.
            
            ```
            const user = {
                activate : true,
                login : function(){
                    console.log('로그인 되었습니다.');
                }
            }
            
            console.log(user.__proto__);                            // [Object: null prototype] {}
            console.log(user.__proto__.constructor);                // [Function: Object]
            console.log(Object);                                    // [Function: Object]
            
            /* Object의 프로토타입 함수 */
            console.log(user.__proto__.constructor === Object);     // true
            
            /* Object의 프로토타입 객체 */
            console.log(Object.prototype === user.__proto__);       // true
            
            function test() {
            
            }
            
            console.log(test.prototype);                            // {}
            ```
            
        - 함수와 프로토타입 객체가 서로 쌍으로 만들어진다.
        - 함수는prototype이라는 자동 생성되는 프로퍼티로 프로토타입을 바라볼 수 있고 (객체) 프로토타입 객체는 constructor 라는 프로퍼티로 함수를 바라볼 수 있다.
        - 객체는 __proto__라는 자동 생성되는 프로퍼티로 자신의 프로토타입 객체를 바라볼 수 있다. (객체)
        - 리터럴 객체는 Object라는 기본 제공되는 함수와 Object의 프로토타입 객체를 통해 만들어졌고 정확하게는 Object의 프로토타입 객체를 상속 받아 만들어 진다. (프로토타입 상속)
            
            ```
            const user2 = {
                activate : false,
                login : function(){
                    console.log("로그인 실패하셨습니다.");
                }
            };
            console.log(user2.__proto__);                           // [Object: null prototype] {}
            
            const student = {
                passion : true
            }
            console.log(student.__proto__);                         // [Object: null prototype] {}
            
            student.__proto__ = user2;
            
            console.log(student.__proto__);                         // { activate: false, login: [Function: login] }
            
            student.login();                                        // 로그인 실패하셨습니다.
            
            /* student의 프로토타입 객체를 user2로 바꾼 이후 user2의 프로퍼티를 물려받아 student의 프로퍼티가 늘어남을 확인 */
            for(let prop in student){
                console.log(prop);                                  // passion, activate, login
            }
            ```
            
        - 모든 객체가 프로토타입을 상속받는 점을 이용해 프로토타입 체인을 활용할 수 있다.
            
            ```
            const greedyStudent = {
                class : 1502,
                __proto__ : student
            };
            
            for(let prop in greedyStudent){
                console.log(prop);                                  // class, passion, activate, login, p
            }
            
            console.log(greedyStudent.activate);                    // false, user2에서 상속
            console.log(greedyStudent.passion);                     // true, student에서 상속
            ```
            
        - proto의 값은 객체 또는 null만 가능하다. 다른 자료형은 무시된다.
            
            ![Untitled](Javascript(ES6)%20629e3cf8505b406ebcb0d0a62c57ae36/Untitled%205.png)
            
- Function Prototype
    - Object Constructor Prototype
        
        ![Untitled](Javascript(ES6)%20629e3cf8505b406ebcb0d0a62c57ae36/Untitled%206.png)
        
        - new 생성자 함수는 생성되는 순간 프로토타입 객체 {}가 생성된다.
        - 객체는 함수의 속성을 물려받는다. 이 때 prototype 객체 역시 상속받을 수 있다.
        - 상속받는 prototype 객체는 함수가 생성된 순간 생성된 프로토타입 객체가 아닌 다른 객체 역시 상속받을 수 있다.
            
            ```
            const user = {
                activate : true,
                login : function(){
                    console.log('로그인 되었습니다.');
                }
            };
            
            function Student(name) {
                this.name = name;
            };
            
            console.log(Student.prototype);                             // {}
            Student.prototype = user;
            
            let std = new Student('홍길동');
            
            console.log(std);                                           // { name: '홍길동' }
            console.log(std.activate)                                   // true
            ```
            
            ![Untitled](Javascript(ES6)%20629e3cf8505b406ebcb0d0a62c57ae36/Untitled%207.png)
            
    - Function Property And Constructor Property
        - 개발자가 특별히 할당하지 않더라도 모든 함수는 기본적으로 "prototype" 프로퍼티를 가진다.
        - 함수의 프로토타입 객체도 "constructor"라는 프로퍼티를 가진다. (디폴트 프로퍼티 설명)
        - 함수와 프로토타입 객체는 디폴트 프로퍼티를 통해 서로 참조할 수 있다.
            
            ```jsx
            function Student() {}
            
            console.log(Student.prototype.constructor == Student);              // true
            
            let student = new Student();
            console.log(student.constructor == Student);                        // true, 해당 객체의 생성자 함수를 알 수 있다.
            ```
            

# Array

- Array
    - 배열은 여러 개의 값을 순차적으로(인덱스를 활용하여) 나열한 자료구조이다.
    - 배열의 생성 방식은 리터럴 방식과 생성자 함수 방식의 두 가지가 있다. 리터럴 방식은 아래와 같이 사용한다.
        
        ```
        /* 1. 배열 리터럴을 통해 배열 생성 */
        const arr = ['바나나', '복숭아', '키위'];
        
        console.log(arr);                               // [ '바나나', '복숭아', '키위' ]
        console.log(arr.length);                        // 3
        console.log(typeof arr)                         // object
        console.log(arr.__proto__);                     // Object(0) []
        ```
        
    - new 생성자 함수 방식은 아래와 같다.
        
        ```
        const arr2 = Array();                           // new 연산자를 안써도 되는 빌트인 생성자 함수 Array()를 통해 배열을 생성할 수도 있다.
        console.log(arr2);                              // []
        
        const arr3 = new Array(10);                     // 전달된 인수가 1개이고 숫자인 경우 해당 length 프로퍼티 값인 배열 생성
        console.log(arr3);                              // [ <10 empty items> ]
        
        const arr4 = Array(1, 2, 3);                    // 전달된 인수가 2개 이상이거나 숫자가 아닌 경우 인수를 요소로 갖는 배열 생성
        console.log(arr4);                              // [ 1, 2, 3 ]
        ```
        
    - 배열의 요소는 자신의 위치를 나타내는 인덱스를 가지며 배열의 요소에 접근할 때 대괄호 표기법을 사용한다.
        
        ```
        console.log(arr[0]);                            // 바나나
        console.log(arr[1]);                            // 복숭아
        console.log(arr[2]);                            // 키위
        ```
        
    - 배열의 인덱스와 length 프로퍼티를 활용해 반복문을 활용할 수 있다.
        
        ```jsx
        for(let i = 0; i < arr.length; i++){
            console.log(arr[i]);                        // 바나나, 복숭아, 키위
        }
        ```
        
    - 자바스크립트에서 일반 객체와 배열은 큰 차이가 없고, 프로퍼티 키가 숫자형이라는 것과 length라는 추가 프로퍼티를 가진다는 정도이다.
        
        ```
        console.log(typeof arr);                        // object
        ```
        
- Differences From Regular Array
    - 자바스크립트의 모든 값이 객체의 프로퍼티 값이 될 수 있으므로 모든 값이 배열의 요소가 될 수 있다.
        
        ```
        const arr = [
            '홍길동',
            20,
            true,
            null,
            undefined,
            NaN,
            Infinity,
            [],
            {},
            function(){}
        ];
        ```
        
    - getOwnPropertyDescriptors : 배열 객체만 가진 프로퍼티(프로토타입 객체의 프로퍼티 말고)를 자세히 보고 싶을 때 사용하는 메서드.
    - writable = true면 값을 수정할 수 있다. 아니면 읽기만 가능
    - enumerable = true면 반복문을 사용해 나열할 수 있다. 아니면 반복문 활용 시 포함 안됨.
    - configurable = true면 프로퍼티 삭제가 가능하다. 아니면 삭제가 불가능.
        
        ```
        console.log(Object.getOwnPropertyDescriptors([1, 2, 3]));
        /*
        {
          '0': { value: 1, writable: true, enumerable: true, configurable: true },
          '1': { value: 2, writable: true, enumerable: true, configurable: true },
          '2': { value: 3, writable: true, enumerable: true, configurable: true },
          length: { value: 3, writable: true, enumerable: false, configurable: false }
        }
        */
        ```
        
- Array Method
    - indexOf : 배열에 해당 값의 인덱스 반환. 없으면 -1 반환
        
        ```
        console.log(`foodList.indexOf('물회') : ${foodList.indexOf('물회')}`);              // 0
        console.log(`foodList.indexOf('삼겹살') : ${foodList.indexOf('삼겹살')}`);          // -1(없으면 -1)
        ```
        
    - includes : 배열에 값 포함 여부 확인.
        
        ```
        console.log(`foodList.includes('물회') : ${foodList.includes('물회')}`);            // true
        console.log(`foodList.includes('삼겹살') : ${foodList.includes('삼겹살')}`);        // false
        ```
        
    - push : 배열의 마지막 요소에 값 추가
    → 데이터 동기화 특징으로 인해, push 전에도 배열은 push된 값을 인지하고 있다.
        
        ```jsx
        const chineseFood = ['짜장면', '짬뽕', '우동'];
        /* push */
        chineseFood.push('탕수육');
        chineseFood.push('양장피');
        
        console.log(`push 후 : ${chineseFood}`);                                            // push 후 : 짜장면,짬뽕,우동,탕수육,양장피
        ```
        
    - pop : 배열의 마지막 요소를 제거한 후 해당 값을 반환한다.
        
        ```
        console.log(`chineseFood.pop() : ${chineseFood.pop()}`);                            // chineseFood.pop() : 양장피
        console.log(`chineseFood.pop() : ${chineseFood.pop()}`);                            // chineseFood.pop() : 탕수육
        console.log(`chineseFood.pop() : ${chineseFood.pop()}`);                            // chineseFood.pop() : 우동
        
        console.log(`pop 후 : ${chineseFood}`);                                             // pop 후 : 짜장면,짬뽕
        ```
        
    - unshift : 배열의 첫번째 요소에 값 추가 후 l배열의 length를 반환한다.
        
        ```
        /* unshift */
        console.log(`chickenList.unshift(): ${chickenList.unshift('간장치킨')}`);           // chickenList.unshift(): 4
        console.log(`chickenList.unshift(): ${chickenList.unshift('마늘치킨')}`);           // chickenList.unshift(): 5
        
        console.log(`unshift 후 chickenList : ${chickenList}`);                             // unshift 후 chickenList : 마늘치킨,간장치킨,양념치킨,후라이드,파닭
        ```
        
    - shift : 배열의 첫 번째 요소 제거 후 해당 값을 반환한다.
        
        ```jsx
        console.log(`checkinList.shift(): ${chickenList.shift()}`);                         // checkinList.shift(): 마늘치킨
        console.log(`shift 후 checkinList : ${chickenList}`);                               // shift 후 checkinList : 간장치킨,양념치킨,후라이드,파닭
        ```
        
    - concat : 두 개 이상의 배열을 결합한다.
        
        ```
        const idol1 = ['서태지와 아이들', '소녀시대'];
        const idol2 = ['HOT' , '잭스키스'];
        const idol3 = ['핑클', '블랙핑크'];
        
        console.log(`idol1 기준으로 idol2 배열을 concat : ${idol1.concat(idol2)}`)                  // idol1 기준으로 idol2 배열을 concat : 서태지와 아이들,소녀시대,HOT,잭스키스
        console.log(`idol1 기준으로 idol2 배열을 concat : ${typeof idol1.concat(idol2)}`)           // idol1 기준으로 idol2 배열을 concat : object
        console.log(`idol3 기준으로 idol1, idol2 배열을 concat : ${idol3.concat(idol1, idol2)}`);   // idol3 기준으로 idol1, idol2 배열을 concat : 핑클,블랙핑크,서태지와 아이들,소녀시대,HOT,잭스키스
        console.log(`ES6 스프레드 연산자 사용하여 concat : ${[...idol1, ...idol2]}`)                // ES6 스프레드 연산자 사용하여 concat : 서태지와 아이들,소녀시대,HOT,잭스키스
        ```
        
    - slice : 배열의 요소 선택 잘라내기
        
        ```
        const front = ['HTML', 'CSS', "Javascript", 'React'];
        
        console.log(`front slice(1, 3): ${front.slice(1, 3)}`);                                     // front slice(1, 3): CSS,Javascript
        console.log(`front: ${front}`);                                                             // front: HTML,CSS,Javascript,React (원본에 지장을 주지 않는다.)
        ```
        
    - splice : 배열의 index 위치의 요소 제거 및 추가
    →splice(index, 제거수, 추가값1, 추가값2, ...)
        
        ```jsx
        console.log(`front.splice(3, 1, "JDBC") : ${front.splice(3, 1, "JDBC")}`);                  // front.splice(3, 1, "JDBC") : React
        console.log(`front : ${front}`);                                                            // front : HTML,CSS,Javascript,JDBC (원본에 영향을 준다.)
        ```
        
    - join : 배열을 구분자로 결합하여 문자열로 반환
        
        ```jsx
        console.log(`snackList.join() : ${snackList.join()}`);                                      // snackList.join() : 사탕,초콜렛,껌,과자
        console.log(`snackList.join('/) : ${snackList.join('/')}`);                                 // snackList.join('/) : 사탕/초콜렛/껌/과자
        ```
        

# Standard Built In Object

- Global Object
    - Built In Global Property
        - 전역 객체는 코드가 실행되기 이전 단계에 자바스크립트 엔진에 의해 어떤 객체보다도 먼저 생성되는 특수한 객체
        - 어떤 객체에도 속하지 않는 최상위 객체
        - Node.js 환경에서는 golbal이 전역 객체, 브라우저 환경에서는 window가 전역 객체
            
            ```
            /* 빌트 인 전역 객체의 프로퍼티 호가인 */
            console.log(Object.getOwnPropertyDescriptors(global));
            
            /* Infinity */
            console.log(global.Infinity);                           
            console.log(10/0);
            
            /* NaN */
            console.log(global.NaN);
            console.log(Number('abc'));
            
            /* undefined */
            console.log(global.undefined);
            var nothing;
            console.log(nothing);
            ```
            
    - Built In Global Function
        - URN : name, 이름으로 된 것, 개념만 있고 실제로 구현은 거의 되지 않았다.
        - URL : 주소,  도메인 (www.naver.com)
        - URI 안에 URN과 URL이 있는 것.
            
            ```
            /* isFinite : 무한한 값인가? */
            console.log(global.isFinite(10));                           // true
            console.log(global.isFinite(Infinity));                     // false
            
            /* isNaN : 숫자가 아닌가? */
            console.log(isNaN(NaN));                                    // true
            console.log(isNaN(10));                                     // false
            
            /* url에서 한글이 안 깨지고 나오게 할 때 사용할 함수 */
            /* encodeURIComponent */
            /* 알파벳, 0~9숫자, -_.!~*'() 문자를 제외하고 인코딩 한다. */
            const uriComp = 'name=홍길동&job=student';
            const encComp = encodeURIComponent(uriComp);
            console.log(encComp);                                       // name%3D%ED%99%8D%EA%B8%B8%EB%8F%99%26job%3Dstudent
            
            /* 나중에 백단에서 url 값으로 넘어오는 한글이 깨지면 사용하게 될 함수 */
            /* decodeURIComponent */
            const decComp = decodeURIComponent(encComp);
            console.log(decComp);                                       // name=홍길동&job=student
            ```
            
- Math
    - Math Property
        
        ```jsx
        /* Math.PI : 원주율 */
        console.log(Math.PI);               // 3.141592653589793
        ```
        
    - Math Method
        
        ```jsx
        // Math.abs
        console.log(Math.abs(-10));
        console.log(Math.abs('-10'));
        console.log(Math.abs(''));
        console.log(Math.abs([]));
        console.log(Math.abs(null));
        console.log(Math.abs(undefined));
        console.log(Math.abs({}));
        console.log(Math.abs('math'));
        console.log(Math.abs());
        console.log('=========================');
        
        // Math.round
        console.log(Math.round(10.1));
        console.log(Math.round(10.9));
        console.log(Math.round(-10.1));
        console.log(Math.round(-10.9));
        console.log(Math.round(10));
        console.log(Math.round());;
        console.log('==========================');
        
        // Math.ceil
        console.log(Math.ceil(10.1));
        console.log(Math.ceil(10.9));
        console.log(Math.ceil(-10.1));
        console.log(Math.ceil(-10.9));
        console.log(Math.ceil(10));
        console.log(Math.ceil());
        console.log('===========================');
        
        // Math.sqrt (square root)
        console.log(Math.sqrt(4));
        console.log(Math.sqrt(-4));
        console.log(Math.sqrt(2));
        console.log(Math.sqrt(1));
        console.log(Math.sqrt(0));
        console.log(Math.sqrt());
        console.log('===========================');
        
        // Math.random
        console.log(Math.random());
        // 1 ~ 100 범위의 난수 추출
        const random = Math.floor((Math.random() * 100) +1);
        console.log(random);
        console.log('===========================');
        
        // Math.pow
        console.log(Math.pow(2, 2));
        console.log(Math.pow(2, -2));
        console.log(Math.pow(2));
        
        // ES7에서 도입 된 지수 연산자를 사용할 수 있다. */
        console.log(2 ** 2);
        console.log(2 ** -2)
        console.log('===========================');
        
        // Math.max
        console.log(Math.max(10));
        console.log(Math.max(10, 20));
        console.log(Math.max(10, 20 , 30));
        console.log(Math.max());
        console.log('===========================');
        
        // Math.min
        console.log(Math.min(10));
        console.log(Math.min(10, 20));
        console.log(Math.min(10, 20, 30));
        console.log(Math.min());
        console.log('===========================');
        ```
        
- Date
    - Date
        
        ```jsx
        /*
            UTC(합정 세계시)       : 국제 표준시로 기술적인 표기에서 사용한다. (영국 런던 기준) (JAVA 기준)
            GMT(그리니치 평균시)   : UTC와 초의 소수점 단위에서만 차이가 나기 때문에 일상에서는 혼용하여 사용한다. (JS 기준)
            KST(한국 표준시)       : UTC + 9시간
        */
        
        /* 1. new Date() */
        /* 1970년 1월 1일 00:00:00(UTC)를 기점으로 몇 밀리초가 지났는지를 계산해서 나온 현재 시스템 시간 */
        console.log(new Date());
        
        /* 2. new Date(milliseconds) */
        console.log(new Date(0));
        console.log(new Date(24 * 60 * 60 * 1000));             // 하루 뒤
        
        /* 3. new Date(dateString): 날짜의 시간을 나타내는 문자열을 인수로 전달하면 지정 된 날짜와 시간을 나타내는 Date 객체 반환 */
        console.log(new Date('Jul 29, 2022 09:00:00'));         // 2022-07-29T00:00:00.000Z
        console.log(new Date('2022/07/26/09:00:00'));           // 2022-07-26T00:00:00.000Z
        
        /* 4. new Date(year, monty[, day, hour, minute, second, millsecond]) */
        /* : 연, 월, 일, 시, 분, 초, 밀리초를 의미하는 숫자를 인수로 전달하면 지정 된 날짜와 시간을 나타내는 Date 객체 반환 */
        /* month(0 ~ 11) */
        console.log(new Date(2022, 1));
        console.log(new Date(2022, 1, 1, 9, 0, 0, 0));
        ```
        
    - Date Method
        
        ```
        /* 02_Date-method */
        
        /* Date.now : 1970년 1월 1일 00:00:00(UTC)를 기점으로 현재 시간까지 경과한 밀리초를 숫자로 반환한다. */
        const now = Date.now();
        console.log(new Date(now));
        
        /* 연, 월, 일, 시, 분, 초, 밀리초 반환 및 설정 */
        const date = new Date();
        console.log(date.getFullYear());
        console.log(date.getMonth());
        console.log(date.getDate());
        console.log(date.getDay());                 // 일요일부터 0~6으로 반환
        console.log(date.getHours());
        console.log(date.getMinutes());
        console.log(date.getSeconds());
        console.log(date.getMilliseconds());
        
        date.setFullYear(2020);
        date.setMonth(0);
        date.setDate(1);
        date.setHours(9);
        date.setMinutes(10);
        date.setSeconds(10);
        date.setMilliseconds(10);
        console.log(date);
        ```
        
- Reg Exp
    - RegExp
        - 정규 표현식(Regular Expression)은 일정한 패턴을 가진 문자열의 집합을 표현하기 위해 사용하는 형식 언어(Formal language)이다.
        - 정규 표현식은 대부분의 프로그래밍 언어와 코드 에디터에 내장되어 있다.
        - 문자열을 대상으로 한 패턴 매칭 기능을 제공하므로 예를 들어 회원 가입 시 필요한 사용자가 입력한 비밀번호의 패턴 확인, 전화번호의 유효성 확인 등의 기능에서 활용할 수 있다.
            
            ```
            /* 검색 대상 */
            const target = 'JavaScript';
            
            /* 1. 정규 표현식 리터럴(/pattern/flag) */
            let regexp = /j/i;                  // 패턴 j, 플래그 i => 대소문자 상관 없이 j가 있는지 판별
            
            /* 2. RegExp 생성자 함수(new RegExp(pattern[, flag])) */
            regexp = new RegExp('j', 'i');
            regexp = new RegExp(/j/, 'i');
            regexp = new RegExp(/j/i);
            
            /* test 메소드 : 정규 표현식 regexp의 패턴을 검색하여 해당 결과를 boolean값으로 반환 */
            console.log(regexp.test(target));   // true
            ```
            
    - RegExp Method
        
        ```jsx
        /* exec : 인수로 전달받은 문자열에 대해 정규 표현식의 패턴을 검색하여 매칭 결과(상세한 정보)를 배열로 반환 */
        console.log(/va/.exec(target));             // [ 'va', index: 2, input: 'Java JavaScript', groups: undefined ]
        console.log(/hello/.exec(target));          // null
        
        /* test : 인수로 전달받은 문자열에 대해 정규 표현식의 패턴을 검색하여 매칭 결과를 boolean으로 반환 */
        console.log(/va/.test(target));             // true
        
        /* match : 대상 문자열과 인수로 전달 받은 정규 표현식과의 매칭 결과를 배열로 반환한다. */
        console.log(target.match(/va/));            // [ 'va', index: 2, input: 'Java JavaScript', groups: undefined ]
        ```
        
    - Flag And Pattern
        - flag의 종류
        - i (case Insensitive) : 대소문자를 구별하지 않고 패턴 적용
        - g (Global) : 대상 문자열 텍스트 내에서 패턴과 일치하는 모든 문자열을 전역 검색
        - m(Multi line) : 문자열의 행이 변경되어도 패턴 검색을 계속 (개행 문자열)
        
        ```
        let target = 'Java JavaScript';
        
        console.log(target.match(/VA/));                        // 일치하는 패턴이 없으므로 null
        console.log(target.match(/VA/i));                       // 처음 일치하는 문자열 하나만 배열로 처리
        console.log(target.match(/VA/ig));                      // 일치하는 문자열 모두 검색(개행 전까지)
        
        /* .: 임의의 문자열 */
        target = 'abcdefg'; 
        console.log(target.match(/../g));                       // [ 'ab', 'cd', 'ef' ]
        
        let arr = target.match(/../g);
        console.log(arr[1]);                                    // cd
        
        /* {m, n} : 최소 m번 최대 n번 반복 되는 문자열(반복 검색)*/
        target = 'a aa aaa b bb bbb bbbb ab aab abb';
        console.log(target.match(/a{2,3}/g));                   // a 최소 2번 ~ 최대 3번 반복 [ 'aa', 'aaa', 'aa' ]
        console.log(target.match(/b{2}/g));                     // b 두번 반복 [ 'bb', 'bb', 'bb', 'bb', 'bb' ]
        console.log(target.match(/b{3,}/g));                    // b 최소 3번 이상 반복 [ 'bbb', 'bbbb' ]
        
        /* +: 앞선 패턴이 최소 한번 이상 반복되는 문자열(반복 검색)({1,}와 같다) */
        console.log(target.match(/b+/g))                        // b가 한개 이상 있는 모든 문자열
        
        /* ?: 앞선 패턴이 하나 있거나 없는 문자열({0,1}) */
        target = 'soul seoul';
        console.log(target.match(/se?oul/g));                   // e가 있거나 없어도 되며 soul, seoul을 찾아준다. */
        
        /* |: or */
        target = 'aa bb cc ee 123 456 _@';
        console.log(target.match(/a|b/g));                      // 'a' 또는 'b' 모두
        
        /* 분해되지 않는 단어 레벨로 검색하기 위해 + 함께 사용 */
        console.log(target.match(/a+|b+/g));                    // 'a' 또는 'b'가 있는 단어
        
        /* []는 or의 의미를 가진다. */
        console.log(target.match(/[ab]/g));                     // 'a' 또는 'b'
        
        /* 영역을 지정하려면 -(하이픈)을 사용(소문자 범위) */
        console.log(target.match(/[a-z]/g));                    // /a|b|c|d...|z/ 와 일치
        
        /* 대소문자(알파벳 모두) */
        console.log(target.match(/[a-zA-Z]/g));                 // a-z, A-Z에 일치
        
        /* 숫자 범위 */
        console.log(target.match(/[0-9]/g));                    // 1~9와 일치
        
        /* \d: 숫자 */
        console.log(target.match(/\d/g));                       // 1~9와 일치
        
        /* \D : 숫자가 아닌 문자 */
        console.log(target.match(/\D+/g));                      // 숫자가 아닌 단어들
        
        /* \w: 알파벳, 숫자, 언더스코어 */
        /* \W: \w의 반대 */
        console.log(target.match(/\w+/g));                      // 알파벳, 숫자, 언더스코어가 있는 문자
        console.log(target.match(/\W+/g));                      // 그 외의 특수기호
        
        /* [...] 내의 ^: not */
        console.log(target.match(/[^0-9]+/g));                  // 숫자가 없는 단어, \D와 같다.
        console.log(target.match(/[^a-zA-Z]+/g));               // 대소문자가 아닌 단어
        
        /* ^: 시작 위치, $: 마지막 위치 */
        target = 'https://www.google.com/https';
        console.log(target.match(/^https/g));                   // https로 시작하는지 검사 [ 'https' ]
        console.log(target.match(/https$/g));                   // https로 끝나는지 검사[ 'https' ]
        
        /* (): 그룹 */
        target = 'test tesk tesa';
        console.log(target.match(/test|tesk|tesa/g));           // [ 'test', 'tesk', 'tesa' ]
        console.log(target.match(/tes(t|k|a)/g));               // [ 'test', 'tesk', 'tesa' ]
        ```
        
    - Example
        
        ```jsx
        /* 1. 특정 단어로 시작하는지 검사하는 경우 */
        const url = 'https://www.google.com';
        const url2 = 'http://www.google.com';
        
        /* http:// 또는 https://로 시작하는지 검사 */
        console.log(/^https?:\/\//.test(url));                  // true
        console.log(/^https?:\/\//.test(url2));                 // true
        
        /* 2. 특정 단어로 끝나는지 검사하는 경우 */
        const fileName = 'js_test.js';
        const fileName2 = 'js_test.com';
        
        /* 문자열이 js로 끝나는지 검사 */
        console.log(/js$/.test(fileName));                      // true
        console.log(/js$/.test(fileName2));                     // false
        
        /* 3. 숫자로만 이루어진 문자열인지 검사 */
        const target = '12345';
        const target2 = '@12345@';
        console.log(/^\d+$/.test(target));                      // true
        console.log(/^\d+$/.test(target2));                     // false
        
        /* 4. 아이디로 사용 가능한지 검사 */
        const id = 'hello123';
        const id2 = '가hello123';
        const id3 = 'hello1234567890'
        /* 알파벳, 숫자로 된 6~12자의 문자로 시작하고 끝남 */
        console.log(/^[A-Za-z0-9]{6,12}$/.test(id));            // true
        console.log(/^[A-Za-z0-9]{6,12}$/.test(id2));           // false
        console.log(/^[A-Za-z0-9]{6,12}$/.test(id3));           // false
        
        /* 5. 핸드폰 번호 양식에 맞는지 검사 */
        const phone = '010-1234-5678';
        const phone2 = '02-1234-5678';
        console.log(/^[0-9]{3}-\d{3,4}-\d{4}$/.test(phone));    // true
        console.log(/^[0-9]{3}-\d{3,4}-\d{4}$/.test(phone2));   // false
        
        /* 6. 특수 문자 포함 여부 검사 */
        const exceptT = 'hello#world';
        const except2 = 'hello@world';
        console.log(/[^A-Za-z0-9]+/.test(exceptT));             // true, 알파벳 및 숫자가 아닌 문자가 들어있다는 의미
        
        console.log(/^[^@]+$/g.test(except2));                  // @ 있으면 false, 없으면 true
        
        /* 그룹 관련 추가예제 */
        let target3 = 'https://www.google.com';
        
        /* 그룹을 두개 만들고 배열에 하나씩 요소로 담고 싶을 때 */
        // let regex = /(https:)([\/a-z\/.]+)/;
        
        /* 첫번째 그룹은 배열의 요소로 뽑고 싶지는 않을 때(단순 ()의 의미는 ()안에 ?:를 붙인다.) */
        let regex = /(?:https:)([\/a-z\.]+)/;                   // https: 가 있어도 되고 없어도 되지만 배열로는 나오지 않는다.
        
        console.log(target3.match(regex));
        
        /* 7. 한글 범위 여부 검사 */
        /* 한글범위 : [가~힣] */
        /* 한글로만 된 2~4글자(이름) */
        let name = '홍길동';
        console.log(/^[가-힣]{2,4}$/.test(name));               // true
        ```
        
- String
    - String
        
        ```jsx
        const obj = new String();
        console.log(obj);                   // 인수 전달하지 않으면 빈 문자열을 할당한 객체 생성
        
        const obj2 = new String('홍길동');
        console.log(obj2);                  // 인수로 문자열 전달 시 전달받은 문자열 할당
        
        /* 첫번째 문자 추출 */
        console.log(obj2[0]);               // 홍
        console.log(obj2.length);           // 3
        
        /* 
            String은 length 프로퍼티(문자열의 문자계수)와 인덱스를 나타내는 숫자 형식의 문자열을 
            프로퍼티 키로, 각 문자를 프로퍼티 값으로 가진다.
        */
        
        /* 문자열이 아닌 값을 인수로 전달했을 경우 문자열로 강제 변환한다. (명시적 타입 변환 참조) */
        const obj3 = new String(100);       // 100 -> '100'
        const obj4 = new String(null);      // null -> 'null'
        
        console.log(obj3[0]);               // 1
        console.log(obj4[0]);               // n
        ```
        
    - String Method
        
        ```jsx
        // 문자열은 변경 불가능한 원시 값이기 때문에 String 래퍼 객체도 읽기 전용 객체로 제공된다.
        const obj = new String('홍길동');
        obj[0] = '김';
        console.log(obj);                                       // [String: '홍길동']
        
        console.log(Object.getOwnPropertyDescriptors(obj));     // writable : false
        console.log('===================================');
        
        // String 객체의 모든 메서드는 String 래퍼 객체를 직접 변경할 수 없고,
        // String 객체의 메서드는 언제나 새로운 문자열을 생성하여 반환한다.
        
        // String.prototype.indexOf
        const str = 'JavaScript';
        console.log(str.indexOf('a'));                          // 문자열에서 a 검색하여 첫번째 인덱스 반환
        console.log(str.indexOf('b'));                          // 검색에 실패하면 -1 반환
        console.log(str.indexOf('a', 2));                       // 검색 시작 인덱스 지정
        
        // 특정 문자열 존재 유무 확인에 사용
        if(str.indexOf('a') !== -1) console.log('ㅁ가 있다.');
        console.log('===================================');
        
        // String.prototype.includes
        console.log(str.includes('a'));                         // 문자열에서 a 검색하여 포함 여부 반환
        console.log(str.includes('b'));                         // 검색에 실패하면 -1 반환
        console.log(str.indexOf('a', 3));                       // 검색 시작 인덱스 지정
        
        // 특정 문자열 존재 유무 확인에 사용
        if(str.includes('a')) console.log('a가 있다.');
        console.log('===================================');
        
        // String.prototype.search
        // 인수로 전달 받은 정규 표현식과 매치되는 문자열을 검색하여 일치하는 문자열의 인덱스를 반환
        console.log(str.search(/a/));
        console.log(str.search(/b/));                           // 검색에 실패하면 -1 반환
        console.log('===================================');
        
        // String.prototype.startWith
        // String.prototpye.endsWith
        console.log(str.startsWith('Ja'));                      
        console.log(str.startsWith('va', 2));                   // 검색 시작 인덱스 지정
        console.log(str.endsWith('pt'));
        console.log(str.endsWith('va', 4));                     // 'Java'가 va로 끝나는지 확인
        console.log('===================================');
        
        // String.prototype.charAt : 인덱스에 위치한 문자 검색하여 반환
        for(let i = 0; i < str.length; i++)
            console.log(str.charAt(i));
        
        console.log('===================================');
        
        // String.prototype.substring : 부분 문자열 반환
        console.log(str.substring(1,4));                        // 두번째 인덱스 위치 바로 이전 문자까지
        console.log(str.substring(1));                          // 두번째 인수 생략 시 문자열 끝까지
        console.log(str.substring(4,1));                        // 인수 교환하여 가능
        console.log(str.substring(-1));                         // 음수는 0으로 취급
        console.log(str.substring(1, 20));                      // length보다 크면 length로 취급
        console.log('===================================');
        
        // String.prototype.slice
        // subString과 동일하게 동작하지만 음수인 인수를 전달하면 가장 뒤에서부터 시작하여 잘라내 반환
        console.log(str.slice(1,4));                            // substring과 동일
        console.log(str.slice(1));                              // substring과 동일
        console.log(str.slice(4,1));                            // 인수 교환 불가능
        console.log(str.slice(-1));                             // 음수는 뒤에서부터
        console.log(str.slice(1, 20));                          // substring과 동일
        console.log('===================================');
        
        // String.prototype.toUpperCase
        // String.prototype.toLowerCase
        console.log(str.toUpperCase);
        console.log(str.toLowerCase);
        console.log('===================================');
        
        // String.prototype.trim
        // 문자열 앞뒤 공백 문자 제거 후 반환
        const str2 = '      JavaScript      ';
        console.log(str2.trim());
        console.log('===================================');
        
        // String.prototype.replace
        // 첫번째 인수로 전달 받은 문자열 또는 정규식을 검색하여 두번째 인수로 전달한 문자열로 치환한 문자열 반환
        console.log(str.replace('Java', 'Type'));
        console.log(str.replace('a', 'b'));                     // 검색 된 문자열이 여럿 존재할 경우 첫번째로 검색된 문자열만 치환한다.
        console.log(str.replace(/j/ig, 'Z'));                   // 첫번째 인수로 정규표현식 전달
        console.log('===================================');
        
        // String.prototype.split
        // 첫번째 인수로 전달한 문자열 또는 정규식을 검색하여 문자열을 구분한 후 분리 된 각 문자열로 이루어진 배열로 반환
        const str3 = 'Hello, Everyone! Nice to see you again.';
        console.log(str3.split(' '));                           // 공백을 구분하여 배열로 반환
        console.log(str3.split(''));                            // 인수로 빈 문자열을 전달하면 각 문자를 모두 분리
        console.log(str3.split());                              // 인수를 생략하면 문자열 전체를 단일 요소로 하는 배열 반환
        console.log(str3.split(' ', 5));                        // 두번째 인수로 배열의 길이 지정
        ```
        

# Class (ES6)

- Class Basic Syntax
    - Class Declarations
        
        ```
        class Student {
        
            /* 생성자는 1개 이상 정의 될 수 없으며 생략할 경우 암묵적으로 정의 된다. 내부적으로는 생성자 함수로 변환된다. */
            constructor(name) {
                this.group = 1501;                                      // 고정 값으로 프로퍼티 초기화
                this.name = name;                                       // 인수로 프로퍼티 초기화
            }
        
            /* class의 메소드는 프로토타입 객체의 메소드가 된다. */
            introduce(){
                console.log(`안녕하세요 저는 ${this.group}강의실 학생 ${this.name} 입니다.`);
            }
        }
        
        let student = new Student('홍길동');
        console.log(student);                                           // Student { group: 1501, name: '홍길동' }
        
        student.introduce();                                            // 안녕하세요 저는 1501강의실 학생 홍길동 입니다.
        console.log(student.__proto__);                                 // {}
        
        /* 이렇게 만들어진 프로퍼티 객체 살펴보기 */
        console.log(Object.getOwnPropertyNames(student.__proto__));     // [ 'constructor', 'introduce' ]
        console.log(Object.getOwnPropertyDescriptors(student.__proto__));
        
        /* class를 활용해 만들어지는 객체도 결국 함수를 기반으로 만든 객체와 원리가 동잃하다. */
        console.log(student.__proto__.constructor === Student);         // true
        
        /* 원리를 적용해서 클래스 문법과 유사하게 기능하는 생성자 함수를 사용해 보자. */
        function Teacher(name) {
            this.group = 1501;
            this.name = name;
        }
        
        Teacher.prototype.introduce = function(){
            console.log(`안녕하세요 저는 ${this.group}강의실 교사 ${this.name}입니다.`)
        }
        
        let teacher = new Teacher("김용승");
        teacher.introduce();
        ```
        
        - new Student()를 호출하면 Student라는 이름을 가진 함수를 만들고 함수 본문은 생성자 메소드 constructor에서 가져온다.
        - 만약 생성자 메소드가 없으면 본문이 비워진 채로 함수가 만들어진다.
        - 클래스 내에 정의한 메소드는 프로토타입 객체에 저장한다.
        - 같은 원리로 생성자 함수를 가지고 유사한 개념을 만들 수도 있다.
        - 클래스와 생성자 함수의 차이점
        - 1. 클래스는 new와 함께 호출하지 않으면 에러가 발생한다.
        - 2. 클래스에 정의 된 메소드는 열거 불가하다. 반면 함수는 메소드도 열거 된다.
            
            ```
            /* 프로토타입 객체에 있는 것은 객체를 for - in 문 돌렸을 때 확인할 수 있다. */
            class Test{
                constructor () {
                    this.name = '홍길동'
                }
            
                introduce(){
                    return `이름 : ${this.name}`;
                }
            }
            
            function Test2(){
                this.name = '홍길동';
                this.introduce = function(){
                    return `이름 : ${this.name}`;
                }
            }
            
            let obj1 = new Test();
            let obj2 = new Test2();
            
            for(let test in obj1){
                console.log(test);                  // name
            }
            
            for(let test in obj2){
                console.log(test);                  // name, introduce
            }
            
            console.log(obj1);                      // Test { name: '홍길동' }
            console.log(obj2);                      // Test2 { name: '홍길동', introduce: [Function (anonymous)] }
            ```
            
    - Class Expression
        - 익명 클래스 표현식
            
            ```
            let Tutor = class{
                teach(){
                    console.log('이해하셨나요~');
                }
            }
            
            new Tutor().teach();
            console.log(Tutor);                                 // 이해하셨나요~, [class Tutor] (기본 생성자)
            ```
            
        - 기명 클래스 표현식
            
            ```
            let Tutee = class MyTutee{
                learn(){
                    console.log('우와~ 이해했어요~');
                    console.log(MyTutee);                       // 내부에서는 에러 발생하지 않음
                }
            }
            
            new Tutee().learn();                                // 우와~ 이해했어요~
            // console.log(MyTutee);                            // MyTutee is not defined
            ```
            
        - 클래스 동적 생성(메소드 호출 시 생성)
            
            ```
            function makeTutee(message){
                return class{
                    feedback(){
                        console.log(message);
                    }
                };
            }
            
            let SecondTutee = makeTutee("100점이에요!");        // [class MyTutee] (생성 된 Calss 객체 반환)
            new SecondTutee().feedback();                       // 100점이에요! (내부 메서드 시행 가능)
            ```
            
        - 클래스도 함수처럼 일급 객체이며 다른 표현식 내부에서 정의, 전달, 반환, 할당이 가능하다.
        
    - Getter, Setter
        
        ```
        class Product{
            constructor(name, price){
        
                /* 아래 두 줄은 각각 name과 price의 setter를 호출 */
                this.name = name;
                this.price = price;
            }
        
            /* getter 함수(프로퍼티에 값을 읽을 때 동작 (값 대입 안할 경우)) */
            get name(){
                console.log('get name 동작');
                return this._name;
            }
        
            get price(){
                console.log('get price 동작');
                return this.price;
            }
        
            /* setter 함수(프로퍼티에 값을 쓸 때 동작 (값을 대입 할 경우 )) */
            set name(value){
                console.log('set name 동작');
                this._name = value;
            }
        
            set price(value){
                console.log('set price 동작');
                this._price = value;
            }
        }
        
        let phone = new Product('전화기', 2000)             // set name 동작 set price 동작
        console.log(phone)                                  // Product { _name: '전화기', _price: 2000 } 실제 프로퍼티는 _name, _price 두 개가 된다.
        console.log(phone.name)                             // name getter 호출
        console.log(phone.price)                             // price getter 무한 호출 (_ 미 사용)
        console.log(phone._name);                           // 전화기
        phone.name = '전화기2';                             // name setter 동작
        ```
        
        ![Untitled](Javascript(ES6)%20629e3cf8505b406ebcb0d0a62c57ae36/Untitled%208.png)
        
        - class 내부에서 constructor 내부의 this는 getter와 setter가 있을 경우 단순 property가 아닌 set 함수 내부의 this._(property)가 된다.
        - 이때 _을 붙이지 않을 경우 set의 this.name은 자기 자신을 호출하는 함수(무한 반복)가 된다.
        - 값을 대입 할 경우 setter, 대입하지 않을 경우 setter로 작동한다.
    - Public Field Declaration
        - 클래스 필드 영역의 경우 Constructor와 달리 this.를 사용하지 않는다.
            
            ```jsx
            class Book {
            
                /* 클래스를 정의할 때 "프로퍼티 이름 = 값"을 써주면 클래스의 필드를 만들 수 있다. */
                /* 최신 브라우저(chrome 72 이상) 또는 최신 Node.js(버전 12 이상)에서만 실행 가능하다. */
                name = "모던 JavaScript";
                price;
                // this.no;                 // this.은 constructor 내부 또는 메소드 내부에서만 작성해야 한다. 
            
                introduce(){
                    console.log(`${this.name}(이)가 그렇게 좋다죠~`);
                }
            }
            
            let book = new Book();
            console.log(book);                                          // Book { name: '모던 JavaScript', price: undefined }
            
            /* 프로토타입 객체에 있는 프로퍼티는 객체가 사용할 수 있다.(프로퍼티 상속) */
            book.introduce();                                           // 모던 JavaScript(이)가 그렇게 좋다죠~
            
            /* 클래스의 메소드는 역시나 프로토타입 객체에 정의가 된다. */
            console.log(Object.getOwnPropertyDescriptors(book.__proto__));
            
            console.log(book.name);                                     // 모던 JavaScript
            ```
            
        - 필드 영역에서 함수를 사용하는 것은 권장되지 않는다.
            
            
- Class Inheritance
    - Inheritance Basic Syntax
        - 상속은 prototype 객체끼리 물려 받는다.
        
        ```jsx
        class Animal {
            constructor(name, weight){
                this.name = name;
                this.weight = weight;
            }
        
            eat(foodweight){
                this.weight += foodweight;
                console.log(`${this.name}(은)는 ${foodweight}kg의 식사를 하고 ${this.weight}kg이 되었습니다.`);
            }
        
            move(lostweight){
                if(this.weight > lostweight) this.weight -= lostweight;
                console.log(`${this.name}(은)는 움직임으로 인해 ${lostweight}kg 감량되어 ${this.weight}kg이 되었습니다.`);
            }
        }
        
        let animal = new Animal("동물", 30);
        
        animal.eat(1);                                              // 동물(은)는 1kg의 식사를 하고 31kg이 되었습니다.
        animal.move(0.5);                                           // 동물(은)는 움직임으로 인해 0.5kg 감량되어 30.5kg이 되었습니다.
        
        class Human extends Animal {
        
        		/* 생성자를 생략하면 super()를 활용해 부모 constructor를 사용하게 된다. */
            // constructor(name, weight){
            //     super(name, weight);
            // }
            
            // /* 상속 받고 추가적으로 프로퍼티를 가지고 싶으면 아래와 같이 작성한다. */
            // constructor(name, weight, language){
            //     super(name, weight);
            //     this.language = language;
            // }
        
            develop(language){
                console.log(`${this.name}(은)는 ${language}로 개발을 합니다. 정말 즐겁습니다.`);
                /* 부모 필드에는 접근 불가능 */ 
                console.log(`${super.name}`);                       // undefined
                /* 메소드는 prototype 객체로부터 상속이기에 접근 가능 */
                super.eat(2);                                       // 수강생(은)는 2kg의 식사를 하고 73kg이 되었습니다.
            }
        }
        
        /* Aniaml에 정의된 constructor 활용 가능 */
        let human = new Human("수강생", 70);
        
        /* Animal에 정의 된 메소드 접근 가능 */
        human.eat(3);                                               // 수강생(은)는 3kg의 식사를 하고 73kg이 되었습니다.   
        human.move(2);                                              // 수강생(은)는 움직임으로 인해 2kg 감량되어 71kg이 되었습니다.
        
        /* Human에 정의 된 메소드 접근 가능 */
        human.develop('Java');                                      // 수강생(은)는 Java로 개발을 합니다. 정말 즐겁습니다.
        ```
        

# Arrow Function

- Arrow Function Feature
    - 화살표 함수는 자체적으로 this를 가지지 않는다. (화살표 함수를 호출한 객체의 의미가 아니다.)
    - 객체의 메소드 안에서 동일한 객체의 프로퍼티를 대상으로 콜백함수를 적용할 때 사용할 수 있다.
    - forEach : Array에서 제공하는 메소드로 배열의 요소별로 할 기능을 콜백 함수로 넘기면 배열의 요소별로 돌아가며 실행함
    - arrow function에서의 this는 자신을 호출한 대상이 아닌, 바깥쪽의 대상을 가리킨다.
    - forEach 안, 콜백 함수 안에서의 this는 자신이 가진 요소별로 번갈아 돌아가며 실행하는 함수가 된다.
    - 내부 익명 함수에서의 this는 자신을 호출한 함수만을 의미한다.
        
        ```
        let theater = {
            store: "건대점",
            titles : ["어벤져스", "공조2", "스파이더맨", "헐크", "라이온킹"],
        
            showMovieList() {
        
                
                /* this -> titles 배열 */
                this.titles.forEach(
                    /* this는 바깥 theater.showMovieList()의 theater) */
                    title => console.log(this.store + ": " + title)
                    /*
                        건대점: 어벤져스
                        건대점: 공조2
                        건대점: 스파이더맨
                        건대점: 헐크
                        건대점: 라이온킹    
                    */
                );
        
                this.titles.forEach(
                    function(title){
        
        								/* this -> function(title) */
                        console.log(this.store + ": " + title);
                        /*
                            undefined: 어벤져스
                            undefined: 공조2
                            undefined: 스파이더맨
                            undefined: 헐크
                            undefined: 라이온킹
                        */
                    }
                )
            }
        };
        
        theater.showMovieList();
        ```
        
    - 화살표 함수는 new와 함께 호출할 수 없다.
        
        ```
        const arrowFunc = () => {};
        const normalFunc = function(){
        };
        
        // new arrowFunc();                                     // 에러발생, arrowFunc is not a constructor
        new normalFunc();                                       // [Function: normalFunc]
        ```
        
    - 화살표 함수는 arguments를 지원하지 않는다.
        
        ```
        (function() {
            const arrowFunc = () => console.log(arguments);     // [Arguments] { '0': 1, '1': 2 }
            // const arrowFunc = function(){
            //     console.log(arguments);                      // [Arguments] { '0': 3, '1': 4 }
            // };
            arrowFunc(3, 4);
        })(1,2);
        ```
        
    - 화살표 함수는 다른 함수의 인수로 전달되어 콜백함수로 사용되는 경우가 많다.
    - 위와 같은 특징들은 콜백 함수 내부의 this가 외부 함수의 this와 다르기 때문에 발생하는 문제를 해결하기 위해 의도적으로 설계 된 것이라 할 수 있다. (화살표 함수를 써야만 하는 경우가 존재한다.)

# Symbol

- Symbol Basic Syntax
    - Symbol은 ES6에서 도입 된 7번째 데이터 타입으로 변경 불가능한 원시 타입의 값이다.
    - Symbol은 다른 값과 중복 되지 않는 유일무이한 값으로 주로 이름 충돌의 위험이 없는 유일한 프로퍼티 키를 만들기 위해 사용된다.
        
        ```
        /* Symbol()을 사용하면 심볼 값을 만들 수 있다. */
        let symbol1 = Symbol();
        
        console.log(typeof symbol1);                        // symbol
        
        /* 심볼 이름은 어떤 값에도 영향을 주지 않는 이름표 역할만 한다. */
        let symbol2 = Symbol("mySymbol");
        let symbol3 = Symbol("mySymbol");
        
        console.log(symbol2);                               // Symbol(mySymbol)
        console.log(symbol3);                               // Symbol(mySymbol)
        console.log(symbol2 == symbol3);                    // false - symbol2와 symbol3는 지닌 이름만 같을 뿐 다른 값이다.
        ```
        
    - 전역 심볼 레지스트리(global symbol registry)에 심볼을 만들고 해당 심볼에 접근하면, 이름이 같은 경우 항상 동일한 심볼을 반환한다.
        
        ```jsx
        let symbol = Symbol.for("id");
        let idAgain = Symbol.for("id");
        
        console.log(symbol);                                // Symbol(id)
        console.log(idAgain);                               // Symbol(id)
        console.log(symbol === idAgain);                    // true - 이름이 같은 두 전역 심볼은 완전히 일치한다.
        
        /* 반대로 Symbol.keyfor(symbol)을 사용하면 이름을 받을 수 있다. */
        console.log(Symbol.keyFor(symbol));                 // id
        ```
        
    - 심볼은 이름이 같더라도 항상 값이 다르므로 이름이 같을 때 값도 같길 원한다면 전역 레지스트리를 사용해야 한다.
    - 전역 심볼 레지스트리는 애플리케이션 곳곳에서 심볼 이름을 이용해 특정 프로퍼티에 접근해야 할 경우 사용할 수 있다.
- Symbol Feature
    
    ```
    /* id 심볼을 생성해서 프로퍼티로 객체에 추가 */
    let id = Symbol("id");                  // id 심볼 생성
    student[id] = 1;                        // student 객체에 [id] (숨김)프로퍼티 추가
    
    console.log(student);                   // { name: '홍길동', [Symbol(id)]: 1 }
    
    /* 아래의 방법들로 확인할 때 symbol을 키로 가지는 프로퍼티는 마치 숨겨진 것처럼 확인이 안된다.(숨김 프로퍼티) */
    console.log(Object.keys(student));      // [ 'name' ]
    console.log(Object.getOwnPropertyDescriptors(student));
    
    for(let key in student){
        console.log(key);                   // name
    }
    
    console.log(student[id]);               // 1
    
    /* 리터럴 객체 안에서 사용할 경우 대괄호를 사용해 심볼형 키를 만들어야 한다. */
    let student2 = {
        name : "유관순",
        age : 16,
        [id] : 2
    };
    
    for(let key in student2) console.log(key);  // name, age    for..in문에서 숨김 프로퍼티는 배제된다.
    ```
    
    - 숨김 프로퍼티는 기존에 작성 된 코드에 영향을 주지 않고 새로운 프로퍼티를 추가하기 위해, 즉 하위 호환성을 보장하기 위해 도입 되었다.

# Spread Syntax

- Spread Syntax
    - 스프레드 문법은 하나로 뭉쳐 있는 여러 값(문자열, 배열, 객체)들의 집합을 전개해서 개별적인 값들의 목록으로 만든다.
        
        ```
        console.log(`가장 큰 값: ${Math.max(10, 30, 20)}`);                             // 가장 큰 값: 30
        
        /* 배열을 인수 목록으로 확장해 보기 */
        let arr = [10, 20, 30];
        console.log(...arr);                                                            // 10 20 30
        console.log(`가장 큰 값: ${Math.max(...arr)}`);                                 // 가장 큰 값: 30
        
        let arr1 = [10, 30, 20];
        let arr2 = [100, 300, 200];
        
        console.log(...arr1, ...arr2);                                                  // 10 30 20 100 300 200
        console.log(`두 배열에서 가장 작은 값 : ${Math.min(...arr1, ...arr2)}`);        // 두 배열에서 가장 작은 값 : 10
        
        /* 일반 값과 혼합해서도 사용 가능하다. */
        console.log(`가장 작은 값: ${Math.min(1, ...arr1, 2, ...arr2, 3)}`);            // 가장 작은 값: 1
        
        /* 배열의 병합에서도 배열에서 제공하는 concat 메소드보다 간단하게 처리할 수 있다. */
        let merged = [10, ...arr1, ...arr2, 2];
        console.log(merged);                                                            // [10, 10, 30, 20, 100, 300, 200, 2]
        
        /* 문자열일 때 */
        let str = "JavaScript";
        console.log(...str);                                                            // J a v a S c r i p t
        console.log([...str]);                                                          // 스프레드 연산자로 문자 하나씩 들어간 배열을 쉽게 만들 수도 있다.
        console.log(Array.from(str));                                                   // 무언가를 배열로 바꿀 때 보편적으로 사용하는 배열의 from 메소드
        ```
        
- Array And Object Copy
    - 배열 복사
        
        ```
        let arr = [10, 30, 20];
        let arrCopy = [...arr];
        
        console.log(arr);                               // [ 10, 30, 20 ]
        console.log(arrCopy);                           // [ 10, 30, 20 ]
        console.log(arr === arrCopy);                   // false
        
        arrCopy.push(50);
        console.log(arr);                               // [ 10, 30, 20 ]
        console.log(arrCopy);                           // [ 10, 30, 20, 50 ]
        ```
        
    - 객체 복사
        
        ```
        let obj = { name : '홍길동', age : 20};
        let objCopy = {...obj};
        
        console.log(obj);                               // { name: '홍길동', age: 20 }
        console.log(objCopy);                           // { name: '홍길동', age: 20 }
        console.log(obj === objCopy);                   // false
        
        objCopy.age = 30;
        console.log(obj);                               // { name: '홍길동', age: 20 }
        console.log(objCopy);                           // { name: '홍길동', age: 30 }
        ```
        

# Destructuring Assignment

- Array Destructuring Assignment
    - Array Destructuring Assignment Basic Syntax
        - 구조 분해 할당을 사용해 배열이나 객체를 각각의 변수로 '분해'하여 연결할 수 있다.
            
            ```jsx
            /* 이름과 성을 요소로 가지는 배열 */
            let nameArr = ["Gildong", "hong"];
            
            // let firstName = nameArr[0];
            // let lastName = nameArr[1];
            let [firstName, lastName] = nameArr;
            
            console.log(firstName);                             // Gildong
            console.log(lastName);                              // hong
            
            /* 반환 값이 배열인 메소드를 활용한 구조분해 할당 예제 */
            // let [firstName2, lastName2] = "Saimdang Shin".split(" ");
            let [firstName2, lastName2] = "Saimdang Shin".match(/[a-z]+/gi);
            
            console.log(firstName2);                            // Saimdang
            console.log(lastName2);                             // Shin
            
            /* 쉼표를 활용하여 필요하지 않은 배열 요소를 버릴 수도 있다. */
            let [firstName3, , lastName3] = ['firstName', 'middleName', 'lastName'];
            
            console.log(firstName3);                            // firstName
            console.log(lastName3);                             // lastName
            ```
            
    - Various Usage
        - 객체의 프로퍼티에도 값을 담을 수 있다. (객체의 프로퍼티 뿐 아니라 대입할 수 있는 대상이면 좌측은 상관 없다.)
            
            ```
            let user = {};
            [user.firstName, user.lastName] = "Gwansoon Yu".split(" ");
            
            console.log(user.firstName);                                // Gwansoon
            console.log(user.lastName);                                 // Yu
            console.log(user);                                          // { firstName: 'Gwansoon', lastName: 'Yu' }
            ```
            
        - 나머지 요소를 한번에 가져오기(rest parameter(나머지 매개변수, 스프레드 연산자가 아니다.))
            
            ```
            let [sign1, sign2, ...rest] = ["양자리", "황소자리", "쌍둥이자리", "개자리", "사자자리"];
            
            console.log(sign1);                                         // 양자리
            console.log(sign2);                                         // 황소자리
            console.log(rest);                                          // [ '쌍둥이자리', '개자리', '사자자리' ]
            ```
            
        - 변수 교환 용도로도 사용할 수 있다.
            
            ```
            let student = "유관순";
            let teacher = "홍길동";
            
            [student, teacher] = [teacher, student];        
            
            console.log(`학생 : ${student}, 교사 : ${teacher}`);        // 학생 : 홍길동, 교사 : 유관순
            ```
            
        - 기본 값을 설정하고 사용할 수도 있다.
            
            ```
            let [firstName4 = "아무개", lastName4 = "김"] = ["길동"];
            
            console.log(firstName4);                                    // 길동
            console.log(lastName4);                                     // 김
            ```
            
- Object Destructuring Assignment
    - Object Destructuring Assignment Basic Syntax
        - 상품명과 색상, 가격을 프로퍼티로 가지는 객체 생성
            
            ```
            let pants = {
                productName: "애기팬츠",
                color : "검정색",
                price : 30000
            };
            ```
            
        - 객체 구조분해 할당은 객체의 프로퍼티와 일치하는 변수를 만들어 대입하는 과정을 줄여서 쓸 수 있다. (순서가 바뀌더라고 상관 없다.)
            
            ```
            let {color, price, productName} = pants;
            
            console.log(productName);                       // 애기팬츠
            console.log(color);                             // 검정색
            console.log(price);                             // 30000
            ```
            
        - {객체 프로퍼티: 목표 변수} 형식으로 작성할 수 있다. (다른 변수에 할당 가능)
            
            ```
            let {color:co, price:pr, productName:pn} = pants;
            console.log(pn);                                // 애기팬츠
            console.log(co);                                // 검정색  
            console.log(pr);                                // 30000
            ```
            
    - Various Usage
        - 기본 객체 생성
            
            ```
            let shirts = {
                productName: "베이직셔츠"
            };
            ```
            
        - 객체에 존재하지 않는 프로퍼티는 기본 값을 생성해서 사용할 수 있다.
        - 또한 콜론과 할당을 동시에 사용할 수 있다.
            
            ```
            let {productName: productName2 = "어떤 상품", color: color2 = "어떤 색상", price : price2 = 0} = shirts;
            
            console.log(`productName2 : ${productName2}`);                  // productName2 : 베이직셔츠
            console.log(`color2 : ${color2}`);                              // color2 : 어떤 색상
            console.log(`price2 : ${price2}`);                              // price2 : 0
            ```
            
        - 프로퍼티가 많은 복잡한 객체에서 원하는 정보만 뽑아오는 것도 가능하다. (이 경우가 주로 사용 된다. )
            
            ```
            let pants = {
                productName : "배기팬츠",
                color : "검정색",
                price : 20000
            };
            
            let {productName : productName3} = pants;
            
            console.log(`productName3 : ${productName3}`);                  // productName3 : 배기팬츠
            ```
            
        - rest parameter ...로 나머지 요소를 한 번에 가져올 수도 있다.
            
            ```
            let {productName: productName4, ...rest} = pants;
            
            console.log(`productName4 : ${productName4}`);                  // productName4 : 배기팬츠
            console.log(`rest.color : ${rest.color}`);                      // rest.color : 검정색
            console.log(`rest.price : ${rest.price}`);                      // rest.price : 20000
            console.log(rest);                                              // { color: '검정색', price: 20000 }
            ```
            
        - rest parameter는 가장 마지막에 와야만 한다.
    - Function Parameters
        
        ```jsx
        let exampleProduct = {
            items: ["Coffe", "Donut"],
            producer: "신사임당"
        };
        
        function displayProduct(producer = "아무개", items = [], width = 10, height = 20){
            console.log(`${producer} ${width} ${height}`);                      // 신사임당 10 20
            console.log(items);                                                 // [ 'Coffe', 'Donut' ]
        }
        
        displayProduct(exampleProduct.producer, exampleProduct.items);
        
        /* 함수의 매개변수에서 구조 분해 할당을 통해 개선해 보자. */
        function displayProduct2({producer = "아무개", items = [], width = 10, height = 20}){
            console.log(`${producer} ${width} ${height}`);                      // 신사임당
            console.log(items);                                                 // [ 'Coffe', 'Donut' ]
        }
        
        displayProduct2(exampleProduct);
        ```
        
        - 함수의 매개변수에서 객체의 구조분해 할당을 사용하면 객체 인수 하나만 던지면 되고 순서에 구애받지 않아도 되는 이점이 있다.

# DOM

- Browser Rendering
    - 브라우저는 HTML, CSS, JavaScript로 작성 된 텍스트 문서를 파싱하여 브라우저 랜더링한다.
    - 파싱(parsing) : 프로그래밍 언어의 문법에 맞게 작성 된 텍스트 문서를 읽어 들여 실행하기 위해 텍스트 문서의 문자열을 토큰으로 분해하고, 토큰에 문법적 의미와 구조를 반영하여 트리 구조의 자료 구조인 파스 트리(parse tree)를 생성하는 일련의 과정이다. 일반적으로 파싱 이후 파스 트리를 기반으로 중간 언어인 바이트 코드를 생성하고 실행한다.
    - 랜더링(rendering): HTML, CSS, JavaScript로 작성 된 문서를 파싱하여 브라우저에 시각적으로 출력하는 것이다.
- DOM(Document Object Model)
    - 브라우저 랜더링 엔진은 HTML 문서를 파싱하여 브라우저가 이해할 수 있는 자료 구조인 DOM을 생성한다.
        
        ![Untitled](Javascript(ES6)%20629e3cf8505b406ebcb0d0a62c57ae36/Untitled%209.png)
        
    - HTML 문서를 파싱한 결과물로서 생성 된 DOM은 HTML 문서의 구조와 정보뿐만 아니라 HTML 요소와 스타일 등을 변경할 수 있는 프로그래밍 인터페이스로서 DOM API를 제공한다. 자바스크립트에서 DOM API를 사용하면 이미 생성 된 DOM을 동적으로 조작할 수 있다.
- NODE
    - HTML 요소는 랜더링 엔진에 의해 파싱되어 DOM을 구성하는 요소 노드 객체로 반환된다. 이때 HTML 요소의 어트리뷰트는 어트리뷰트 노드로, HTML 요소의 TEXT CONTENT는 TEXT NODE로 변환 된다.
        
        ![Untitled](Javascript(ES6)%20629e3cf8505b406ebcb0d0a62c57ae36/Untitled%2010.png)
        
    - HTML 요소 간에는 중첩 관계에 의해 계층적인 부자 관계가 형성 된다.
    - 이러한 HTML 요소 간의 부자 관계를 반영하여 HTML 문서의 구성 요소인 HTML 요소를 객체화한 모든 노드 객체들을 트리 자료 구조로 구성한다.
    - 노드 객체들로 구성 된 트리 자료 구조를 DOM(Document Object Model)이라 한다. 이에 DOM 트리라는 명칭을 쓰기도 한다.
- Get Element Node
    - getElementById
        - 변수 이름 앞에 $표시를 붙여서 해당 변수는 html 요소를 지니고 있음을 나타내기 위한 관례상 네이밍 규칙
            
            ```
            const $elem = document.getElementById('area1');
            
                    console.log($elem);
            ```
            
        - 해당 아이디를 가진 요소들 중 첫 번째 요소 하나만 반환한다.
        - 요소의 스타일 속성을 이용하여 배경색 변경(요소에 css 적용)
            
            ```
            // $elem.style.backgroundColor = 'skyblue';
                    $elem.style['background-color'] = 'skyblue';                // 대괄호([])를 이용하면 기존의 css 속성명을 사용할 수 있다.
            
                    const $elem2 = document.getElementById('area2');
            	      $elem2.style.backgroundColor = 'blue';                      // WEB API
            ```
            
        - id 속성 값을 그대로 딴 이름으로 요소에 접근도 가능하다.
            
            ```
            /* id 속성 값을 그대로 딴 이름으로 요소에 접근도 가능하다. */
                    // let area2 = 1;                   // 하지만 id값과 동일한 이름의 전역 변수가 있을 경우 이름 충돌이 있어 지향하는 것이 좋다.
                    area2.style.backgroundColor = 'red';
            ```
            
        - id에 해당하는 HTML 요소가 없을 시 null을 반환한다.
            
            ```html
            const $elem3 = document.getElementById('area3');
                    console.log($elem3);                // null
            ```
            
        - 자바스크립트는 script 태그 안에 작성하며 어디에 만들어도 상관 없고 따로 만들어도 한 영역으로 취급 된다. 하지만 주로 관련있는 html 태그 근처나 body 태그의 종료 태그 직전에 작성하는 편이다.
    - getElementsByTagName
        - HTMLCollection으로 반환(유사배열, 반복이 가능(이더러블))
            
            ```
            const $lists = document.getElementsByTagName("li");
                    console.log($lists);
                    console.log($lists.length);
            ```
            
        - 기본 반복문을 통한 배경색 변경 처리
            
            ```
            let changeColor = 0;
                    for(let i  = 0; i < $lists.length; i++){
                        console.log($lists[i]);
                        $lists[i].style.backgroundColor = 'rgb(130, 220, ' + changeColor + ")";
                        changeColor += 50;
                    }
            ```
            
        - HTMLCollection 객체를 배열로 반환하여 순회하며 color 프로퍼티 값을 변경한다.
            
            ```
            [...$lists].forEach(lists => lists.style.color = 'blue');
                    Array.from($lists).forEach(list => list.style.color = 'red');
            
                    const $drink = document.getElementById('drink');
                    const $listsFromDrink = $drink.getElementsByTagName('li');
                    console.log($listsFromDrink);
            
                    [...$listsFromDrink].forEach(list => list.style.color = 'orange');
            ```
            
        - 해당 태그 이름이 존재하지 않으면 빈 HTMLCollection을 반환한다.
            
            ```html
            const $noElem = $drink.getElementsByTagName('a');
                    console.log($noElem);
            ```
            
    - getElementsByClassName
        - class 이름이 drink인 요소들을 가져와서 HTMLCollection 타입으로 반환
            
            ```
            const $drinks = document.getElementsByClassName("drink");
                    console.log($drinks);
                    console.log($drinks.length);
            
                    [...$drinks].forEach($drinks => $drinks.style.color = 'red');
            
                    const $coffe = document.getElementsByClassName('drink coffe');
                    console.log($coffe[0].innerHTML);       // 가져온 요소의 프로퍼티에 접근할 수 있다. 그리고 innerHTML은 내부 HTML요소까지 읽어온다.
                    console.log($coffe[0].innerText);       // innerText는 내부 Text 노드만 읽어온다.
                    console.log($coffe[0].__proto__);
            
                    const $available = document.getElementById('available');
                    const $availableDrinks = $available.getElementsByClassName('drink');
                    console.log($availableDrinks);
            ```
            
        - 해당 class를 갖는 요소가 없을 경우 빈 HTMLCollection 객체 반환
            
            ```html
            const $noElem = $available.getElementsByClassName('somac');
                    console.log($noElem);
            ```
            
    - querySelector
        - querySelector를 사용하면 css의 선택자를 그대로 활용할 수 있다.
            
            ```
                    const $area = document.querySelector('.area');              // 첫번째 하나의 요소만 반환
                    // const $area = document.querySelectorAll('.area');        // 해당하는 요소들을 NodeList 타입으로 반환한다.
                    console.log($area);
            
                    $area.style.backgroundColor = "gray";
            
                    const $first = $area.querySelector('p');
                    console.log($first);
            
                    $first.style.color = "white";
            
                    const $noElem = document.querySelector(".noElem");
                    console.log($noElem);
            ```
            
        - querySelectorAll로 반환된 NodeList는 HTMLCollection과 달리 forEach 메소드를 바로 사용할 수 있다.
        
        ```
                const $lists = document.querySelectorAll('ul#list > li');
                console.log($lists);
                [...$lists].forEach(list => list.style.background = 'yellow');      // 스프레드 문법으로 배열로 반환한다고 문제가 되진 않는다.
                $lists.forEach(list => list.style.background = 'yellow');
        
                const $foodList = document.getElementById('list').querySelectorAll('.food');
                console.log($foodList);
        
                $foodList.forEach(food => food.style.color = 'white');
        ```
        
    - HTMLCollection
        - HTMLCollection과 NodeList는 DOM API가 여러 개의 결과 값을 반환하기 위한 DOM 컬렉션 객체이다.
        - HTMLCollection은 getElementByTagName, GetElementByClassName 메소드가 반환하는 객체이다.
        - 또한, 노드 객체의 상태 변화를 실시간으로 반영하는 살아있는(live) DOM 컬렉션 객체이다.
        - HTMLCollection은 들어있는 값이 실시간으로 반환 되므로 의도치 않은 결과가 발생할 수 있다.
            
            ```
            const $whiteList = document.getElementsByClassName('white');
            
                    console.log($whiteList);
            
                    // for(let i = 0; i < $whiteList.length; i++){
                    //     $whiteList[i].className = 'black';
                    // }
            
                    /* 해결1. 역순으로 반복을 돌린다. */
                    // for(let i = $whiteList.length - 1; i >= 0; i--){
                    //     $whiteList[i].className = 'black';
                    // }
            
                    /* 해결2. while문을 활용한다.*/
                    // let i = 0;
                    // while($whiteList.length > 0){
                    //     $whiteList[i].className = 'black';
                    // }
            
                    /* 해결3. HTMLCollcetion 객체를 사용하지 않고 배열로 반환하는 것이 가장 권장된다. */
                    [...$whiteList].forEach(list => list.className = 'black');
                    // Arrays.from($whiteList).forEach(list => list.className = 'black');
            ```
            
    - NodeList
        - NodeList는 querySelectorAll 메소드가 반환하는 객체이다.
        - 실시간으로 노드 객체의 상태를 변경하지 않기(non-live)때문에 HTMLCollection의 부작용을 해결할 수 있다.
            
            ```html
            <ul id="list">
                    <li class="red">빨간 휴지 줄까~ 파란 휴지 줄까~</li>
                    <li class="red">빨간 휴지 줄까~ 파란 휴지 줄까~</li>
                    <li class="red">빨간 휴지 줄까~ 파란 휴지 줄까~</li>
                </ul>
            
                <script>
                    const $redList = document.querySelectorAll('.red');
                    $redList.forEach(list => list.className = 'blue');
                </script>
            ```
            
- Node Traversing
    - Child Node
        - children: 자식 노드 중 요소 노드만 탐색하여 HTMLCollection에 담아 반환
            
            ```
            /* HTMLCollection 타입으로 자손 요소 노드만 포함(텍스트 노드X) */
            console.log($element.children);
            ```
            
        - firstElementChild: 첫 번째 자식 요소 노드 반환
            
            ```
            /* 첫 번째 자식 요소 노드 탐색*/
            console.log($element.firstElementChild);
            ```
            
        - lastElementChild: 마지막 자식 요소 노드 반환
            
            ```html
            /* 마지막 자식 요소 노드 탐색 */
            console.log($element.lastElementChild);
            ```
            
    - Parent Node
        
        ```
        <h1>02. 부모 노드 탐색</h1>
        
            <script>
                console.log('=== HTML ===');
                console.log(document.documentElement);      // DOM Tree상에서 document 아래 요소들
                console.log("=== HEAD ===");
                console.log(document.head);                 
                console.log("=== BODY ===");
                console.log(document.body);
                console.log("=== HEAD의 parentNode ===");
                console.log(document.body.parentNode);
            </script>
        
            <ul id="lists">
                <li class="coffe">커피</li>
                <li class="coke">콜라</li>
                <li class="milk">우유</li>
            </ul>
        
            <script>
                const $coke = document.querySelector('.coke');
        
                console.log($coke.parentNode);              // ul 태그 반환
                /* parentNode는 자식 태그의 직속 부모 태그로 찾아가는 것이며, 연달아 찾아갈 수 있다. */
                console.log($coke.parentNode.parentNode);   // body 태그 반환 
            </script>
        ```
        
    - Sibling Node
        - previousElementSibling: 형제 요소 노드 중 자신의 이전 형제 요소 노드를 탐색하여 반환
            
            ```
            const $element = document.getElementById('element');
                    console.log($element);
            
                    for(let prop in $element){
                        if(prop == 'previousElementSibling')
                        console.log(prop);
                    }
            
                    /* 형제 요소 중 이전 요소 노드를 가진 프로퍼티 활용 */
                    // const previousElementSibling = $element.previousElementSibling;
                    const {previousElementSibling} = $element;              // 객체 구조분해 할당으로 할 수도 있다.
                    console.log(previousElementSibling);
            ```
            
        - nextElementSibling: 형제 요소 노드 중 자신의 다음 형제 요소 노드를 탐색하여 반환
            
            ```
                     /* 자식 중 첫번째 요소 노드를 가진 프로퍼티 활용 */
                    const {firstElementChild} = $element;
                    console.log(firstElementChild);
            
                    /* 현재 요소 중 이후 요소 노드를 가진 프로퍼티 활용 */
                    const {nextElementSibling} = firstElementChild;
                    console.log(nextElementSibling);
            ```
            
- DOM Modification
    - innerHTML
        - innerHTML은 요소 노드를 취득하거나 변경할 때 사용한다.
        - 태그 엘리먼트의 값을 읽거나, 변경할 때 innerHTML속성을 사용한다.
            
            ```
            const $area = document.getElementById('area');
            
                    /* 읽어온 요소가 내부에 가지는 값 출력 */
                    console.log($area.innerHTML);
            
                    /* 노드 추가(+=를 활용해 문자열 텍스트 노드를 추가) */
                    $area.innerHTML += '값 추가';
            
                    /* 노드 교체(요소 노드와 텍스트 노드를 같이 적용할 수도 있다.) */
                    $area.innerHTML = '<h1>innerHTML</h1>속성으로 값 변경';
            
                    /* 노드 삭제 */
                    $area.innerHTML = '';
            ```
            
        - innerHTML 프로퍼티를 사용한 DOM 조작은 구현이 간단하고 직관적이라는 장점이 있다.
        - 하지만 새로운 요소를 삽입할 때 삽입될 위치를 지정할 수 없다는 단점도 있다.
        - (insertAdjacentHTML 메소드를 이용하면 해결할 수 있다.)
        - innerHTML 프로퍼티를 사용하면 XSS(Cross Site Scripting Attack)에 취약하므로 위험하다는 단점도 있다. HTML 마크업 내에 자바스크립트 악성 코드가 포함 되어 있다면 파싱 과정에서 그대로 실행 될 가능성이 있다.
        - sanitizer를 활용, 이를 막을 수 있다.
            
            ```
            // $area.innerHTML = `<img src='x' onerror = 'alert("바보")'>`;
            
            const sanitizer = new Sanitizer();
            $testImg = `<img src='x' onerror = 'alert("바보")'>`;
            $area.setHTML($testImg, sanitizer);
            
            /* DOMPurify라고 하는 추가 라이브러리에 관한 설명이 있는 사이트 */
            // https://github.com/cure53/DOMpurify
            ```
            
    - InsertAdjacentHTML
        - insertAdjacentHTML(position, DOMString) 메소드는 기존 요소를 제거하고 새로 만들어 추가하지 않고(innerHTML과 달리) 기존 요소 제거 없이 새로운 요소만 추가하게 된다.
        - 그리고 위치를 지정해 새로운 요소를 삽입할 수 있으며 첫번째 인수로 전달할 수 있는 position관련 문자열은 'beforebegin'. 'afterbegin', 'beforeend', 'afterend' 4가지이다.
        - insertAdjacentHTML 메소드는 기존 요소에 영향을 주지 않고 새롭게 삽입 될 요소만을 파싱하여 자식 요소로 추가하므로 기존의 자식 노드를 모두 제거하고 처음부터 새롭게 자식 노드를 생성하며 자식 요소로 추가하는 innerHTML 프로퍼티보다 효율적이고 빠르다.
        - 단, HTML 마크업 문자열을 파싱하므로 크로스 사이트 스크립팅(XSS)에 취약하다는 점은 동일하다.
            
            ```html
            const $area = document.getElementById("area");
            
                    $area.insertAdjacentHTML('beforebegin', `<h1>beforebegin 테스트</h1>`);
                    // $area.insertAdjacentHTML('beforebegin', '<h1>beforebegin 테스트</h1><input type="text" onblur="alert(`바보`);">');
                    $area.insertAdjacentHTML('afterbegin', `<h1>afterbegin 테스트</h1>`);
                    $area.insertAdjacentHTML('beforeend', `<h1>beforeend 테스트</h1>`);
                    $area.insertAdjacentHTML('afterend', '<h1>afterend 테스트</h1>');
            ```
            
    - Node Create Append
        - createElement(tagName): 인수로 전달받은 태그 이름에 해당하는 요소 노드를 생성하여 반환
        - createTextNode(text): 인수로 전달받은 텍스트 값으로 텍스트 노드를 생성하여 반환
        - appendChild(childNode): 인수로 전달받은 노드를 appendChild 메소드를 호출한 노드의 마지막 자식 노드로 추가 (innerHTML과 유사)
        - 단일 노드 생성과 추가
            
            ```
            const $drink = document.getElementById('drink');
            
                    /* 요소 노드 생성 */
                    const $li = document.createElement('li');               // <li><li>
            
                    /* 텍스트 노드 생성 */
                    const textNode = document.createTextNode('콜라');       // '콜라'
            
                    $li.appendChild(textNode);                              // <li>콜라</li>
            
                    $drink.appendChild($li);
            ```
            
        - 복수 노드 생성과 추가
            
            ```
            const $food = document.getElementById('food');
            
                    /* 배열 안에 들어있는 값만큼 li태그 만들어 노드 생성하기 */
                    ['된장찌개', '고등어구이', '순대국'].forEach( text => {
                        // console.log(text);
            
                        const $li = document.createElement('li');
                        // const textNode = document.createTextNode(text);
                        // $li.appendChild(textNode);
            
                        $li.textContent = text;             // 요소 노드의 자식 노드로 텍스트 노드를 추가하는 것 보다 textContent 사용이 간편하다.
                    
                        $food.appendChild($li);
                    });
            ```
            
- Attribute
    - Attribute
        - getAttribute/setAttribute 메소드를 사용하면 프로퍼티를 통하지 않고 요소 노드에서 메소드를 통해 직접 HTML 어트리뷰트 값을 취득하거나 변경할 수 있다.
        - getAttribute를 통해 쉽게 요소의 어트리뷰트를 가져오거나 setAttribute를 통해 요소의 어트리뷰트를 수정할 수 있다.
            
            ```jsx
            const $input = document.getElementById('username');
            
                    console.log($input.attributes);             // 요소의 어트리뷰트들이 담긴 프로퍼티
            
                    const inputValue = $input.getAttribute('value');
                    console.log(inputValue);
            
                    $input.setAttribute('value', 'user02');
            ```
            
        - hasAttribute(attributeName) 메소드를 사용하면 특정 HTML 어트리뷰트가 존재하는지 확인할 수 있다.
        - removeAttribute(attributeName) 메소드를 사용하면 특정 HTML 어트리뷰트를 삭제할 수 있다.
            
            ```
            const $nickname = document.getElementById('nickname');
            
                    /* 해당 요소가 어트리뷰트를 가지고 있는지 확인 */
                    console.log($nickname.hasAttribute('name'));            // false
            
                    console.log($nickname.hasAttribute('value'));           // true
            
                    /* value라는 어트리뷰트를 가지고 있다면 해당 value 어트리뷰트 삭제 */
                    if($nickname.hasAttribute('value')){
                        $nickname.removeAttribute('value');
                    }
            
                    /* value 어트리뷰트 삭제 후 확인 */
                    console.log($nickname.hasAttribute('value'));           // false
            ```
            
    - Attribute And Property
        - HTML 어트리뷰트: HTML 요소의 초기 상태를 지정하며 변하지 않는다. 어트리뷰트 노드에서 관리 되며 값을 얻거나 변경하려면 getAttribute/setAttribute 메소드를 사용한다.
        - DOM 프로퍼티: 사용자가 입력한 최신 상태를 관리한다. 사용자의 입력에 의한 상태 변화에 반응하여 언제나 최신 상태를 유지한다.
            
            ```
            const $user = document.getElementById('username');
            
                    /* oninput: 사용자가 입력 필드에 값을 입력할 때마다 동작하는 이벤트 */
                    $user.oninput = () => {
                        console.log(`value 프로퍼티 값: ${$user.value}`);                       // input 태그의 입력값에 따라 실시간 반영
                        console.log(`value 어트리뷰트 값: ${$user.getAttribute('value')}`);     // 초기값 고정
                    };
            
                    const $nickname = document.getElementById('nickname');
                    $nickname.value = 'JSMaster';
            
                    console.log(`value 프로퍼티 값: ${$nickname.value}`);
                    console.log(`value 어트리뷰트 값: ${$nickname.getAttribute('value')}`);
            ```
            
        - getAttribute 메소드로 취득한 어트리뷰트 값은 언제나 문자열이다. 하지만 DOM 프로퍼티로 취득한 최신 상태 값은 문자열이 아닌 Boolean일 수 있다.(어트리뷰트 값이 없는 어트리뷰트일 경우)
            
            ```
            const $checkbox = document.getElementById('check');
            
                    console.log($checkbox.getAttribute('checked'));         //'' -> (없으면) null
            
                    console.log($checkbox.checked);                         // true -> (없으면) falseconst $checkbox = document.getElementById('check');
            
                    console.log($checkbox.getAttribute('checked'));         //'' -> (없으면) null
            
                    console.log($checkbox.checked);                         // true -> (없으면) false
            
                    function test(){
                        if($checkbox.checked == true){
                            console.log("체크됨");
                            $checkbox.style['width'] = '100px';
                            $checkbox.style['height'] = '100px';
                        } else{
                            $checkbox.style['width'] = '10px';
                            $checkbox.style['height'] = '10px';
                        }
                    }
            ```
            
- Style
    - Inline Style
        
        ```
        <div style="color:white; border: 1px solid black;">AREA</div>
            <script>
                const $area = document.querySelector('div');
        
                // console.log($area.getAttribute('style'));
                console.log($area.style);                           // CSSStyleDeclaration 타입의 객체로 넘어온다.
        
                /* 인라인 스타일 추가 */
                /* 단위 지정이 필요한 프로퍼티의 값은 반드시 단위를 지정해야 한다. */
                $area.style.width = '100px';
                $area.style.height = '100px';
        
                /* 
                    CSSStyleDeclaration 객체의 프로퍼티 : 케멀 케이스(낙타봉 표기법)
                    CSS 프로퍼티 : 케밥 표기법
                */
                $area.style.backgroundColor = 'lightgray';          // 마침표 표기법
                $area.style['background-color'] = 'yellow';         // 대괄호 표기법
            </script>
        ```
        
    - ClassName과 ClassList
        - className의 경우는 하나의 클래스 어트리뷰트 값으로 덮어씌울 때 쓴다.
        - 복수의 클래스 어트리뷰트 값을 추가 및 제거 하기 위해서는 classList를 활용해 DOMTokenList 타입의 객체에서 제공하는 메소드를 활용해야 한다.
            
            ```jsx
            const $area = document.querySelector('.area');
            
                    /* 클래스명 덮어쓰기 */
                    // $area.className = 'circle';     // 프로퍼티 className을 통해 class 어트리뷰트값 수정( area -> circle)
            
                    console.log($area.classList);   // 요소가 가진 class 어트리뷰트 값을 DOMTokenList 타입의 객체로 반환
            
                    /* classList(DOMTokenList타입의 객체)에서 제공하는 add(...className)으로 class 어트리뷰트 값을 추가할 수 있다. */
                    $area.classList.add('lightgray');
                    $area.classList.add('circle');
            
                    /* classList에서 제공하는 remove(...className)으로 class 어트리뷰트 값을 제거할 수 있다. */
                    $area.classList.remove('circle');
            ```
            
        - 해당 요소의 클래스 어트리뷰트 값을 하나씩 접근할 수 있다.
            
            ```
            console.log($area.classList[0]);
            console.log($area.classList[1]);
            console.log($area.classList[2]);
            ```
            
        - contain(className)은 인수로 전달된 문자열과 일치하는 클래스가 class 어트리뷰트 값으로 포함되어 있는지 확인한다.
            
            ```
            console.log($area.classList.contains('circle'));        // true
            console.log($area.classList.contains('yellow'));        // false
            ```
            
        - replace(oldClassName, newClassName)는 첫 번째 인수로 전달된 문자열을 두 번째 인수로 전달한 문자열로 변경한다.
            
            ```
            $area.classList.replace('lightgray', 'yellow');
            ```
            
        - toggle(className)은 class 어트리뷰트에 인수로 전달한 문자열과 일치하는 클래스가 존재하면 제거하고 존재하지 않으면 추가한다.
            
            ```
            $area.classList.toggle('yellow');
            ```
            

# Event

- Event Handler
    - Event Handler Attribute
        - 이벤트 핸들러는 이벤트가 발생했을 때 브라우저에 호출을 위임한 함수
        - 이벤트가 발생 했을 때 브라우저에게 이벤트 핸들러의 호출을 위임하는 것을 이벤트 핸들러 등록이라고 하며, 이벤트 핸들러를 등록하는 방법은 3가지 이다.
        - 이벤트 핸들러 어트리뷰트 방식 : HTML 이벤트 핸들러 어트리뷰트(on 접두사 + 이벤트 타입) 값으로 함수 호출문을 할당하여 이벤트 핸들러를 등록하는 방식이다.
        - 주의할 점은 함수 참조가 아닌 함수 호출문(소괄호까지 작성)을 할당한다는 점이다.
            
            ```
            <button onclick="console.log('클릭했네?'); alert('클릭했네?');">클릭해보세요</button>
                <button onmouseover="hello('수강생')">마우스를 올려보세요</button>
                <button onclick="(() => {console.log('test');})()">클릭해 보세요</button>
            
                <script>
                    function hello(name){
                        alert(`${name}씨, 마우스 올리지 마세요!`);
                    }
                </script>
            ```
            
    - Event Handler Property
        - DOM 노드 객체는 이벤트에 대응하는 이벤트 핸들러 프로퍼티를 가지고 있다.
        - 이벤트 핸들러 프로퍼티의 키는 이벤트 핸들러 어트리뷰트명과 동일하며(on 접두사 + 이벤트 타입) 이벤트 핸들러 프로퍼티에 함수를 바인딩(대입)하면 이벤트 핸들러가 동작한다.
        - 이벤트 핸들러 어트리뷰트 방식과의 차이점은 프로퍼티 방식은 하나의 이벤트 핸들러만 바인딩 할 수 있다는 단점이 있다는 점이다.
            
            ```html
            <button id="btn">클릭해보세요</button>
                <script>
                    const $button = document.getElementById('btn');
            
                    // $button.onclick = function(){
                    //     alert('DOM 프로퍼티 방식으로 이벤트 핸들러 등록 완료!');
                    // }
            
                    $button.onclick = () => alert('DOM 프로퍼티 방식으로 이벤트 핸들러 등록 완료!');
                </script>
            ```
            
    - Add Event Listener
        - 요소 노드에서 제공하는 addEventListener 메소드를 사용하는 방식이다.
        - addEventListener("eventType", functionName)의 형태로 사용한다.
        - 첫 번째 매개변수에는 이벤트 타입, 두 번째 매개변수에는 이벤트 핸들러를 전달한다.
        - addEventListener 메소드 방식은 프로퍼티 방식으로 등록 된 이벤트 핸들러에 아무런 영향을 주지 않는다. (혼용 가능)
        - 동일한 요소에 동일한 이벤트가 발생할 때 이벤트 핸들러는 등록 된 순서대로 호출한다.
            
            ```
            <button id="btn">클릭해보세요</button>
            
                <script>
                    const $button = document.getElementById('btn');
            
                    $button.addEventListener('click', function(){
                        alert('클릭했네');
                    });
            
                    /* 프로퍼티 방식의 이벤트 핸들러 등록을 중간에 추가 */
                    $button.onclick = function(){
                        console.log('이벤트 핸들러 프로퍼티 방식으로 이벤트 핸들러 등록!');
                    }
            
                    $button.addEventListener('click', function(){
                        console.log('addEventListener 메소드 방식으로 이벤트 핸들러 등록!');
                    });
                </script>
            ```
            
    - Event Handler Remove
        - removeEventListener 메소드를 사용한다.
        - 이벤트 핸들러 제거 시에는 기본적으로 이벤트 핸들러의 이름이 존재해야 한다.
        - 단, 프로퍼티 방식이나 어트리뷰트 방식으로 추가된 이벤트 핸들러는 프로퍼티에 null을 넣음으로써 제거할 수 있다.
            
            ```jsx
            <button id="btn">클릭해보세요</button>
            
                <script>
                    const $button = document.getElementById('btn');
            
                    const handleClick = () => alert('클릭했네요');
                    
                    /* 이벤트 핸들러 등록 */
                    $button.addEventListener('click', handleClick);
            
                    /* 이벤트 핸들러 제거 */
                    $button.removeEventListener('click', handleClick);
            
                    /* 이벤트 핸들러 등록 */
                    /* 등록한 이벤트 핸들러를 참조할 수 없으므로 제거할 수 없다. */
                    $button.addEventListener('mouseover', () => alert('마우스 올렸네요.'));
                    // $button.removeEventListener('mouseover');   // 작동 안됨
                </script>
            
                <button id="btn2">더블 클릭해보세요</button>
            
                <script>
                    const $button2 = document.getElementById('btn2');
            
                    const handleDbClick = () => alert('더블클릭했네요');
            
                     /* 프로퍼티 방식으로 이벤트 핸들러 추가 */
                     $button2.ondblclick = handleClick;
            
                     /* removeEventListener로는 제거가 안된다. */
                     $button2.removeEventListener('dblclick', handleClick);
            
                     /* null을 할당하면 프로퍼티 방식으로 등록된 이벤트 핸들러가 제거된다. */
                     $button2.ondblclick = null;
                     
                </script>
            ```
            
- Event Object
    - Event Object
        - 이벤트 발생 시 이벤트에 관련한 다양한 정보를 가진 이벤트 객체가 동적으로 생성된다.
        - 생성된 이벤트 객체는 이벤트 핸들러의 첫 번째 인수로 전달된다.
            
            ```
            <h1 class="message">아무곳이나 클릭해보세요. 클릭한 좌표를 알려드리겠습니다.</h1>
            
                <script>
                    const $msg = document.querySelector('.message');
            
                    /* 이벤트 핸들러로 사용 될 함수 */
                    /* 클릭 이벤트에 의해 생성된 이벤트 객체는 이벤트 핸들러의 첫번째 인수로 전달 된다. */
                    function showCoords(e){
                        // console.log("실행 되나?");
                        // console.log(e);
                        console.log(`clientX: ${e.clientX}, clientY: ${e.clientY}`);
                    }
            
                    $msg.onclick = showCoords;
                </script>
            ```
            
        - 이벤트 핸들러 등록을 어트리뷰트 방식으로 등록했다면 동적으로 생성되는 이벤트 객체를 활용하기 위해 반드시 event라는 이름의 인수를 넘겨 주어야 한다.
            
            ```html
            <div class="area" onclick="showDivCoords(event);">
                    이 영역 내부를 클릭해 보세요. 클릭한 좌표를 알려드리겠습니다.
                </div>
            
                <script>
                    const $area = document.querySelector('.area');
            
                    function showDivCoords(e){
                        // console.log(e);
                        console.log(`clientX: ${e.clientX}, clientY: ${e.clientY}`);
                    }
                </script>
            ```
            
    - This
        - 이벤트 핸들러 어트리뷰트 방식의 경우 이벤트 핸들러에 의해 일반 함수가 호출되고, 함수 내부의 this는 전역 객체 window를 가리킨다.
        - 이벤트 핸들러 호출 시 인수로 전달한 this는 이벤트가 발생한 DOM 요소를 가리킨다. (이벤트를 발생 시킨 요소)
            
            ```html
            <button onclick="handleClick1();">클릭해보세요1</button>
            <button onclick="handleClick2(this);">클릭해보세요2</button>
            <button onclick="handleClick2(this);">클릭해보세요3</button>
            
            <script>
                function handleClick1(){
                    console.log(this);              // Window 전역 객체 호출
                }
            
                /* 하나의 함수로 각각 다른 요소, 다른 이벤트에 이벤트 핸들러로 등록 할 수도 있다. */
                function handleClick2(button){      // 넘어오는 요소(this로 넘긴 요소)에 대한 처리를 해 두면 재활용이 가능하다.
                    console.log(button);            // 클릭한 button(2) 요소
                }
            
                // function handleClick3(button){
                //     console.log(button);         // 클릭한 button(3) 요소
                //     button.innerHTML = `<p> 클릭?? </p>`;
                // }
            </script>
            ```
            
        - 이벤트 핸들러 프로퍼티, addEventListener
            
            ```
            <script>
                  const $btn1 = document.getElementById('btn1');
                  const $btn2 = document.getElementById('btn2');
            
                  /* 프로퍼티 방식 */
                  $btn1.onclick = function(e){
            
                      /* this와 이벤트 객체의 currentTarget은 동일하다. (이벤트 발생 시킨 요소) */
                      console.log(this);                              // button
                      console.log(e.currentTarget);                   // button
                      console.log(this === e.currentTarget);          // true
                  }
            
                  /* addEventListener 메소드 방식 */
                  $btn2.addEventListener('click', function(e){
                      console.log(this);                              // button
                      console.log(e.currentTarget);                   // button
                      console.log(this === e.currentTarget);          // true
                  });                 
            </script>
            ```
            
        - 화살표 함수 (this의 의미가 다르다.)
        - 화살표 함수로 정의한 이벤트 핸들러 내부의 this의 상위 스코프의 this를 가리킨다.
        - 화살표 함수는 함수 자체의 this 바인딩을 갖지 않는다는 점에 유의해야 한다.
            
            ```
            <script>
                  const $btn3 = document.getElementById('btn3');
                  const $btn4 = document.getElementById('btn4');
            
                  /* 프로퍼티 방식 */
                  $btn3.onclick = e => {
                      console.log(this);                              // window 객체
                      console.log(e.currentTarget);                   // button
                      console.log(this === e.currentTarget);          // false
                  };
            
                  /* addEventListener 방식 */
                  $btn4.addEventListener('click', e => {
                      console.log(this);                              // window 객체
                      console.log(e.currentTarget);                   // button
                      console.log(this === e.currentTarget);          // false
                  });
            
            </script>
            ```
            
    - Event Type Example
        - Mouse Event
            - mousedown/mouseup
                
                ```
                $btn.onmousedown = (e) => {
                    $area.insertAdjacentHTML("beforeend", 'mousedown button=' + e.button + "<br>");
                };
                $btn.onmouseup = (e) => {
                    $btn.insertAdjacentHTML("beforeend", 'mouseup button=' + e.button + '<br>');
                };
                ```
                
            - mouseover/mouseout
                
                ```
                $btn.onmouseover = (e) => {
                    $area.insertAdjacentHTML('beforeend', "mouseover button=" + e.button + "<br>");
                };
                $btn.onmouseout = (e) => {
                    $area.insertAdjacentHTML('beforeend', "onmouseout button=" + e.button + "<br>");  
                };
                ```
                
            - mousemove
                
                ```
                $btn.onmousemove = (e) => {
                    $area.insertAdjacentHTML('beforeend', "mousemove button=" + e.button +"<br>");
                }
                ```
                
            - click
                
                ```
                $btn.onclick = (e) => {
                    $area.insertAdjacentHTML('beforeend', "click button=" + e.button + "<br>");  
                };
                $btn.ondblclick = (e) => {
                    $area.insertAdjacentHTML('beforeend', "dblclick button=" + e.button + "<br>");  
                };
                ```
                
        - Prevent Select Copy
            - mousedown, mousemove 이벤트가 발생할 때 나타나는 브라우저 기본 동작을 막으면 글씨 선택을 막을 수 있다.
            - copy 이벤트가 발생할 때 나타나는 브라우저 기본 동작을 막아 복사를 막을 수도 있다.
            - 브라우저 기본 동작을 막는 방법은 이벤트 객체의 preventDefault 메서드를 호출하는 방법과 이벤트 핸들러 함수 반환 값을 false로 지정하는 것이 있다.
                
                ```
                <script>
                        const $span = document.querySelector('span');
                        $span.onmousedown = (e) => e.preventDefault();
                
                        const $div = document.querySelector('div');
                        $div.oncopy = () =>{
                            alert('복사 불가능합니다.');
                            return false;
                        }
                    </script>
                ```
                
        - Keyboard Event
            - 키보드 이벤트는 keydown, keyup가 있다.
            - event.key : 문자
            - event.copy : 물리적인 키 코드
            - Ex. 소문자 a를 입력하면 event.key = a event.code = KeyA, 대문자 A를 입력하면 event.key = A event.code = KeyA
                
                ```
                <script>
                        const $message = document.querySelector("input[type=text]");
                        const $area = document.querySelector(".area");
                
                        $message.onkeydown = (e) => {
                            $area.insertAdjacentHTML('beforeend', 'keydown key=' + e.key + ", code= " + e.code + "<br>");
                        };
                        $message.onkeyup = (e) => {
                            $area.insertAdjacentHTML('beforeend', 'keyup key=' + e.key + ", code= " + e.code + "<br>");
                        }
                
                    </script>
                ```
                
        - Input Event
            - Form 요소 다루기 :
                - input, textarea : input.vale 또는 input.checked(checkbox 또는 radio)
                - select.option : option 하위 요소들을 담고 있는 컬렉션
                - select.value : 현재 선택 된 option의 값
                - select.selectedIndex : 현재 선택 된 option의 번호(인덱스)
            - 폼 태그 및 폼 태그 안에 있는 값(요소)들은 인덱스 또는 id, name으로 선택할 수 있게 다양한 선택자가 존재한다.
                
                ```
                <script>
                  // 폼 취득
                  // 문서 내 모든 form들을 HTMLCollection 타입으로 반환
                  console.log(document.forms);
                  console.log(document.forms.memberjoin);                     // name 속성 값
                  console.log(document.forms[0]);                             // index 값
                  const $form = document.forms.memberjoin;
                  
                  // 요소 취득
                  // form 내 사용자 입력 양식을 HTMLFormControlsCollection 타입으로 반환
                  console.log($form.elements);                                // id 또는 class 어트리뷰트 이름으로 구성 됨
                  console.log($from.elements.username == $form.username);     // $form.username과 같다.
                
                  /* name 또는 class 어트리뷰트명 */
                  const $username = $form.username;
                  console.log(`$form.username.value : ${$username.value}`);
                  $username.value = '유관순';
                
                  const $gender = $form.gender;
                  console.log(`$form.gender[1].checked : ${$gender[1].checked}`);
                  $gender[1].checked = true;
                
                	/* select 태그의 특징들 */
                  const $age = $form.age;
                  console.log($age.options);                                  // HTMLOptionsCollection 타입의 option 태그들
                  $age.options[2].selected = true;
                  age.selectedIndex = 3;                                      // id가 'age'인 것으로 바로 요소 선택, 인덱스로 3번 인덱스(4번째 요소 선택)
                  age.value = '50';                                           // value가 50이 되게 설정
                
                	/* textarea 태그의 특징들 */
                  const $introduce = $form.introduce;
                  console.log($introduce.vale);
                  $introduce.value = 'value';
                  $introduce.textContent = 'textContent';
                	/*
                      시작태그와 종료태그가 있지만 text node를 수정하기 위한 textContent 프로퍼티로는 수정할 수 없다. 
                      종료태그가 없는 input 태그처럼 value 프로퍼티를 수정해야 들어있는 값이 변경된다.
                  */
                
                </script>
                ```
                
            - focus : 사용자가 폼 요소를 클릭하거나 tab 키를 눌러 요소로 이동 했을 때 발생하는 이벤트
            - blur : 사용자가 다른 곳을 클릭허거나 tab 키를 눌러 다음 폼 필드로 이동했을 때 발생하는 이벤트
            - 또한, focus, blur 메소드로 요소에 포커스를 주거나 제거할 수 있다.
                
                ```
                <script>
                		$username.onfocus = (e) => {
                		    e.target.classList.toggle('lightgray');
                		};
                		
                		$username.onblur = (e) => {
                		    e.target.classList.toggle('lightgray');
                		};
                </script>
                ```
                
            - focus 이벤트는 해당 입력 필드에서만 동작하고 버블링 되지 않는다. 버블링이 필요하다면 focusin, focusout 이벤트를 사용한다.
            - 생성 된 이벤트 객체는 이벤트를 발생 시킨 DOM 요소 이벤트 타깃(event target)을 중심으로 DOM 트리를 통해 전파된다.
                - 1. 캡처링 단계(capturing phase) : 이벤트가 상위 요소에서 하위 요소 방향으로 전파
                - 2. 타깃 단계(target phase) : 이벤트가 이벤트 타깃에 도달
                - 3. 버블링 단계(bubbling phase) : 이벤트가 하위 요소에서 상위 요소 방향으로 전파
                
                ```
                <script>
                        $form.addEventListener('focus', (e) => e.target.classList.add('focused'));
                        $form.addEventListener('blur', (e) => e.target.classList.remove('focused'));
                				
                				/* form 태그의 하위 요소에서 이벤트가 발생해도 상위 요소인 form 태그에 단 이벤트 핸들러가 동작한다.(버블링) */
                        $form.addEventListener('focusin', (e) => {
                            console.log(e.target);
                            e.target.classList.add('focused')
                        });
                        $form.addEventListener('focusout', (e) => e.target.classList.remove('focused'));
                    </script>
                ```
                
            - change 이벤트는 요소 변경이 끝나면 발생하는 이벤트다.
            - select/checkbox/radio의 경우 선택 값이 변경 된 직후 이벤트가 발생하지만 텍스트 입력 요소인 경우 변경 후 포커스를 잃었을 때 이벤트가 발생한다.
                
                ```
                <script>
                    $username.addEventListener('change', () => alert('username change!'));              // input type = "text"
                    $age.addEventListener('change', () => alert('age change!'));
                    $introduce.addEventListener('change', () => alert('introduce change!'));            // textarea
                </script>
                ```
                
            - input 이벤트는 키보드 이벤트와 달리 어떤 방법으로든 값을 변경할 때 발생한다.
            - 예를 들어 마우스를 사용하여 글자를 붙여 넣거나 음성 인식 기능을 사용해서 글자를 입력할 때도 반응한다.
                
                ```
                <script>
                    $introduce.addEventListener('input', (e) => {
                        let len = e.target.value.trim().length;
                        $form.querySelector('span').textContent = len;
                        if(len >= 500) document.getElementById('limit').style.color = 'red';
                        else document.getElementById('limit').style.color = 'black';
                    });
                </script>
                ```
                
        - Form Submit Event
            - submit은 폼을 제출할 때 동작하는 이벤트로 폼을 서버로 전송하기 전 내용을 검증하여 폼 전송을 취소할 때 사용한다.
            - 폼을 전송하는 방법으로는 (1) input type="submit" 또는 input type="image" 클릭 (2) input 필드에서 Enter 키 누르기가 있다.
                
                ```
                <form method="GET" action="https://search.naver.com/search.naver" name="search" id="formId">
                    <input type="text" name="query" placeholder="검색할 키워드를 입력하세요">
                    <input type="image" src="data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2wCEAAkGBwgHBgkIBwgKCgkLDRYPDQwMDRsUFRAWIB0iIiAdHx8kKDQsJCYxJx8fLT0tMTU3Ojo6Iys/RD84QzQ5OjcBCgoKDQwNGg8PGjclHyU3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3N//AABEIAHAAoAMBIgACEQEDEQH/xAAbAAACAwEBAQAAAAAAAAAAAAAFBgMEBwIBAP/EADgQAAIBAwIEBAQEBgICAwAAAAECAwAEEQUhBhITMSJBUWEUcYGRFTKhsQcjwdHw8UJiUuE0cpL/xAAZAQADAQEBAAAAAAAAAAAAAAABAgMEAAX/xAAfEQADAQADAQADAQAAAAAAAAAAAQIRAxIhMQRBYVH/2gAMAwEAAhEDEQA/AHngvSF0nQ4YyB1ZBzyH3NE788kJIq2CFXHkKoahIDEwNZLfhvhegSPDtlu7VcjiHahDP0ZlJOAe1HbVgQD3qcse0fPadSM9gaWr/ToY2mjljJimBDgDINOIOBtS7xUHitfiI/zRnO3nVZ+km9WGLcQ6NLoWpBUdjE/jgmAxkenzFGuHuKD0hbX477CT+9OF7ZWXEekhHdQjDmRgMtG/rWWatp11o189pdbMpyGA2ceorVmrDHNuXqGW0tRa3l3NGRyzNkY9KnllPrS1Y608I6cqkjy9qJJfifICMPpmmFb16dahITEaWJieoSfWmK4kTptlqATozsXUeDPeiAsWeWZRTLZqQoDUsW8iwnLEiilvq9ugHMWOe2BXBDtxypAxHcVxw9YxTO80oBBO1D5dVWRuj0mXmGQSe9W7JdQMZESFI+5KLzGktNrEPLSes71t9NtrtHzl1O4oH+IpZ3dzcWLBXmGObG4Fe6nExbxqS4/5NtVLStLu9R1KGwt1HUlbAJOwHqTQSSQO7b1Fzh7Q9Q4r1BljJS2Q/wA+4bsv9zWx6NpNnotilnp8JWNfzORu59Sa50TTYtH0e20yB4yYwWkKd2PqaJtJ005i2FG3M3nSOtGSCbzZB3oRqFyqj8/Kaje5Ztl2FQTdMj+bvUKWm6fGCb27jbCkgNmr2k3j5CSE48jVHU49O6ZYgBgMiq2hX0dxIYg4LxncH0qaWFK+D5bNzLUOsQie0dD5iubOQqni86lu2DQnJ8qdURc+ma20Js3kMLEpzlWjz4o29PcVT1lYtWh/D7sNzqCYJsbof6ipuKufTNSF9E2I28Mq4yGHuK60FI7q9F7ZzKQO8RIB9ex/ataeowXPWmgbo3BUUAt7nVp+UOwATGDkH9ARRi/tdNsLe7jtYBNnOAM5QbE7+wBq1r05v7xOhzxPGcvHgfP7bUMkuo+u0UcbPL0iGBbHNzeePUbj61y0Av6rJH+HdLAjVm6gVWxyqQSB7nzoTp6tJayB154ojnBPn/n70evmhigmSOARsGLuGwQgxuo7+v2q1Fb2NtZ2lkI2FvPAZZSWwXc45hn/AK4NOAU7bTXPMtwvI5lIbmXIwBk7ip7O0NvN8RLEWiQgOqknGMeIf55U266kSyrFCIoAy7wyNtHuM7j1/b6VQ0qwuCzuka8kYZpsn8/chfbOD/hoHFTpx2eqRG4QiOZOcB188b/p2o5p0M8Ehl07qPbtLGDCdjk5Ixn5Z+tcyxT6jFbawkDokBkgbkbmBYHAAz8hvVi2+Mi067eKYrJy+Hw45m5iB59z+1BhQdivrO+1eOxubVfhmXk6kq4y+2T60wxcN6RpmL22WOBkPNzduYny3pMMVlqR+GkXqy285R2RuTBPfzHhAH1+9Mdri63cgRs3Ri5mGEx2Ix9f0qTHRynV/Ef5TTczDKqIzgD50YvLaZ7aNpE5m3DAN2oNd8P2+k25km1KVriVs8zSHb2Cjv5d6ms725a6W0aV3WAYLOPzE0Hkopxp1R40eRsT96oXkV7026Mq/UURiYNGN68mLFe1K0maU2nhn+sXV/EGEkKsB5qaW9I1qay12K5OREW5ZF9jTrxFGTGxXvWf3AKysrUVKJ8rafhvNjcCa2DBsjGSRXr3PLsoAHvuTSZwJrfU01IXbMiHkIz9jTnHGh3J53Pdqj8eF5aa0VuJIjdwOJE8JzuaF8Ex2Npb3LXWVlhOQ+QfCe3ypq1eFWibCFiO2KD3FlbWOnGBQOtKD1zncZ8qvxP9Gb8mV4wFql1NNePe23NlmzgLgq3r3oboiy3HEBguY8GRHXnIwo2wRt7eflTFpdzpnM9rqDJJIMBMkDIHnV2PhK5nvYL2xu+gwIeIcgO3pkE7H18q0LwyCjxLL+Ha5HaRZAMqg7Aknt98GmLivTJZNNsL61HjtAOmzHuvff74o5xJwG2sX1hfoenLEAsqMebsCfrvVfiKK4sojI9m8hVUDqX5kJDA9vvtXNnAHXoRqFvBeTIZJWROULjbJ3PvzEdh6e9HOGLERPKVIeSdw7B/CeXt2GdsfKqwDJaRhnLu6eIch28OAMjtgD65Pzozp8UsVu5iixJApWJCf+JB9M/9ft86RvAr0ivdKZeAJ4rdhDJbK7B+XPPy+wPnj50H/h841HQbxL0oIrfLdZiPCSCPvufvWiaZpNzDoyWc0mCY8Ek55SQO36/eh2ncFWNjw9PopeR4p2JnlLcrPk5xt/quTC0Zzw9p1xg3kaiQvIGQKA2B5H57impbqWy5ZJIGVhLzMoJYIAMD3b9NzVuz0zSdFusW9w8swwDAj8/KBtjHcVzd6vpN/etayMsVzEw5opDhj6bUrGRdsdW+ISaeJURzEWi503XPfP8AuoOHJpdQDT3EoeR2J5uXFRwWkltKkhlLyrzKYi2wUnv55G9e8PmO1v7i2Q/kckbbYNSv4aOH6ziyOUqa5EyR80YDexqhp8uVBHajcMayIe31oIp/RF1a7VndXUxyDyakjUipvfDuK1XiHSIruJgygNjbHcVlt1YyWl5Ikrc2D4SPSqST5Drh+/OnaiJCcRscNWtWd/FJbc3NzDHrWLSjltnPvTp/D3UlJFlcsMOvgZv2pLjfTuK88Y9zaXc6ja/EiYW8MY5kBGSxFZzrA1G6kmhgjMaBszyr2A9hWrWYEdvJG7bqMN3NKttccPSSy2KXEZmaYl1mz4j6b4/aqwkl4T/IbbFHRri00Zy0eli65tuoHyxHc5G9O+j/AIHrcRe1iuNMnOMTW9wQVz5kDH6jFAdV0Z7S7E+n/hwiZuUhm5cexydqh+E1fTCL8pFbW+Q4a3YNG/sfM/MCqGcO67b8Z8MW/wAVputfilup2int1D47nxDv29qL6drsXFvCcty8IiuEDJKhOeSQD1+v61xZanc31hNp9welJKsnTkXOYv8AxO/l/qs64c1u90vWdWsSgIkkJkORgNvv9aDYQ/YwiAxxleYKvkNhv39u1PfCkXPaySSMctjK5zWawX7COeQk5k9d9t807abqDROGDgKVGFC+WPKoVeFZhsufxI4j1DS7K00/RMfieoPyRNgHkHm30oZZ8JSCzW74t1jUL6THN0UlMaeuOVO/1pcm1Se6/ibBLqQT4e2TKHB2AyAdvXPvTRfy3V9eyvKpaNciLlOVXfswyCCR51RMVr9E9xfyaXGsOlWFpZWKYUlwQMnsQQMfrQbUIV1O2M2s2EPVJIjlhYMyeQbI2Oc7CqN5p120ZudYvIILWcHLLbhyVG+ygbeW5JqzoF5wfpPTNt1r+4XxCQwHAX222FBnEckuqadBDHqFtK1rjlS9XdlHllRvtXVhdtHcvIHD5AJIOc0dveNdPkuBHFZvcJkDPL5n0HnSnxDItrqsgh5IkcD+UqgY+dLS1FuF5RJoV5j+U53Ttn0pstLlSqjNZqk5gmVwfY/KmaxvuoFAPlQ5J6sfgvusYZ1K6MpMdtEZXHffApU1Thu/vpOrmGNv/EZO1OtkismAQR60QW2jAycUivDRUJ/TIL7g3U1tiqPCzt2UZGaJaFwRcRmKW+uWQpuEh7g//atIks0Muds1I0KKAABXPlEXDCKNlYhmBeSQ7YxzHce9Vtb4X024QsbVS58x/mQfei6TJEwBwB71T1niTTrNAskkfUbZU6i8zfTNCbbDUz8aEZOErSOZjPdzDPMArue3od6KW9rZwxfDQs6wKjY8BwTvnf69x33qkeIGN+BK8Jhc4EEgBZT6ggkn7UReUMwKwqgYgczvyj6kgftWqdZ59pJ+E8TlIeQSIWVdjETuBjAwfIfTypH4hiFnc3MuAJJSpZh+tXtdvrq0Mkd0ZrZeXnDIOp51YutM/HLC4js5Vmuo4gSo3Kt+b1+X3rqfgsrWALS5JiTl32P0+dErLVZonAkJZc/aglihinls53ktriNCQJFGJMenvXcYkj1C3tYYpb64dQ46TcoUN+XNTqE0VltDxZ2vX1C3nMb5EeM5zlfJfX/VNensnURmVSRgBUXGOxO/2oGbb8H0SxOoK5unLIioMmRypblx5DY15wzdaxMRPNbfA2kkasjTtzEsTuMbf0pYbw6ktNAa0tpjzywqxO/iGaG6s1ukTRW9tC7t3BHLn/PaoZrmYQifq3TK2QqpAFyfmcH+le20Ny8RMsU5Q78hZMUzeeDRHYBvbT3GC7qhY5yi+Xptn7muYuFbLq9Voy0nfnLEn9TRt7iOKUxsgVh5MN6lNygXcr9FqLo1qVHwxaRywxV/Sp5Im3HOB29QKGRNzMc+VWraYxXCP5Dv8q28k9pPM4q61o56fqgxgE/LG9FYtUAHc0sPCJUDRZUkeRqNeH5bqbnmdyB2HMa85rD1l6hwfVEIDZGRVWbiG0hUm4uoox6c29BhwrEy7hvuarxcNQPPymIKo9qGDYS3/E/xr/D6Nb9eVtutIvhX3we9EtI0eeMda7u5JLhh4mz+mBsKs6XolvZHJUFaJTkSIY07elMjvEC9a0u2uNPMl3c3J5PEAr/0ORVXhS8tNQDwQGQ9Mcg6kOAD5Zwaoa/Nd3cvwUCmWKLBnZT4R/0HqT9h79qp6c9/JIixP8LHF+ZBgKF88H/Pl2FaOOvDFzz+8CHEGm3EXX+Hje0Zx4mjbroAPMpjYHz86v8AA7TpeSGUWxhlY5dSFYkYAyKMxIl3ZBXGexDflLH+n71ABdxuzSxq8Q/LJgF+X0pq1kJxHH8SuHbPUtNjvIWCX1sMx4GeqPNT/eh38LtMsbXS47y5cNeXA7lfyLjYUcvIbzULJ7Szj6GY8PM4AwMbAVzw7oU+m28Rbxo7dmbGPoaDXgyzSxrkV5eFYYNQigj7gJFzyMM4zuNqq6TosqSI0qAvEpQTXIV5W33xjYDb3+lMPQnZ+TDKh9QPD7VFfXlvp8bK0iq+PFk7j0NCVgX6yjrEkdr0wLcy8ozhGwTVCTiK6C8sdo8YPZmIP7f+6itr6K+mxchS47N6iiRtVUYjxv5eVTqtfhq456+MBm4vJZTLKHZiPXauZ7i9I5BGiD1xk/aj8Nko9PlUxtowdwM0illHaMYuYliXw98b1Ap2FdTziQ4qFCDtXos8deDZw7dxyRiGQjmXYZputpYgAMj71lcMrwyCSNsMKOWuvsABJIFI9RWXk42nqN3FzJrGaH1Y8bMDVWXkDh170r2+qrOf/kD6VZklidd5z/8AqotNF5oLTahEnheQMfJRUctzczqIbePo83d27/ShsU9lb+JnjUjzZt6h1Di3TLcKq3KvLkYVBmh1bH7Svoz2umR2luF59+7b9yaUeJ9Rt7O9ht4yrlpACi/8jnc0qXXFWqajeDMzxQDJ6YPpnGaHqnQl6qlmbuTnNXnjz0zcn5CaxGgWOtP17aK2MhBYZB7A9960jTrq3dHOVY9jk7E4yaxvRZ5BFzSOpHcHIyPSiFxrEyD+VIygsMDGPOqGZmywwQgFwqhnHiI86tKqCM4A232rJbDifVIU6SSM67eLI+1Fn4j1Ce3CLzNjvuN/aubR2DpqOpwW1szmRScEr70m3Uc2oystzGHYgEE+QoZBfQEyTTpIcA5X03/vVKHjBrS9aOJI2ZjjlZiCanWv4W4+qesOLozWvKY5cY7cwq00eoJgqFPyNUW4uFxHyT2rRMfU5q/p2qRNGoLA7VJzhqVpo9S6vk2aLP1rprm9YbQ1Y+Ot+bDMtd/iFsBsy0MBq/w//9k=">
                    <!-- <button type="submit">네이버 검색하기</button> -->
                </form>
                
                <script>
                    // const $form = document.forms[0];
                    const $form = document.forms.search;
                
                    $form.onsubmit = function(){
                        let keyword = this.querySelector('input[name=query]').value;
                        if(!keyword){           // input 태그에 아무 값도 입력하지 않았을 때
                            alert('검색어를 입력하지 않았습니다!')
                            return false;
                        }
                    };
                </script>
                ```
                
        

# Timer

- Timer Method
    - Timeout
        - setTimeout(func, time, [argument, ...]);
        - setTimeout 함수는 두번째 인수로 전달 받은 시간(ms, 1/1000초)으로 단 한번 동작하는 타이머를 생성한다.
        - 이후 타이머가 만료 되면 첫 번째 인수로 전달 받은 콜백 함수가 호출 된다.
        - 콜백 함수에 전달 해야 하는 함수가 존재하는 경우 세 번째 이후의 인수로 전달할 수 있다.
            
            ```jsx
            <script>
                /* setTimeout은 비동기적(동시에)으로 동작한다. */
                setTimeout(function(){
                    console.log('1초 후');
                }, 1000);                                                                           // 1초 후 시행
                setTimeout(() => console.log('1초 지났습니다.'), 1000);                             // 1초 후 콜백함수 동작
                setTimeout((msg) => console.log(`1...2...3... ${msg}`), 3000, '3초 Timeout');       // 3초 후 인수를 받으며 동작하는 콜백함수 동작
            </script>
            ```
            
        - setTimeout 함수는 생성된 타이머를 식별할 수 있는 고유한 타이머 id를 반환한다.
        - 반환된 id를 clearTimeout 함수의 인수로 전달하여 타이머를 취소할 수 있다.
            
            ```
            <button onclick="clearTimeoutFunc();">당장 취소</button>
            
                <script>
                    const timeId = setTimeout(() => console.log('나는 곧 취소된다.'), 2000);
                    console.log(timeId);
                    // clearTimeout(timeId);                                                               // setTimeour의 고유 ID를 받아 취소시킨다.
            
                    /* 이벤트 핸들러 역할을 하는 곳에서 clearTimeout을 하면 원하는 이벤트 시점에 타이머를 취소시킬 수도 있다. */
                    function clearTimeoutFunc(){
                        console.log('지금 바로 취소');
                        clearTimeout(timeId);
                    }
                </script>
            ```
            
    - Interval
        - setInterval(func, time, [arg1, ...]);
        - setInterval 함수는 두 번째 인수로 전달받은 시간(ms)으로 반복 동작하는 타이머를 생성한다.
        - 이후 타이머가 만료될 때마다 첫 번째 인수로 전달 받은 콜백 함수가 반복 호출 된다. 이는 타이머가 취소될 때까지 계속된다.
        - 콜백 함수에 전달해야 하는 인수가 존재하는 경우 세번째 이후의 인수로 전달할 수 있다.
            
            ```
            <script>
                let count = 1;
                const timerId = setInterval(() => {
                    console.log(count++);
                    if(count == 6) {
                        clearInterval(timerId);
                        console.log('interval timer 종료');
                    }
                }, 1000);
            </script>
            ```
            
    

# BOM

- Window
    - 자바스크립트가 돌아가는 플랫폼을 호스트(host)라고 부른다.
    - 호스트 환경이 웹 브라우저일 때 사용하며 할 수 있는 기능은 개괄적으로 아래와 같다.
    - window
         ㄴ DOM (document, ...)
         ㄴ BOM (location, navigator, screen, history, ...)
         ㄴ JavaScript (Object, Array, Function, ...)
    - 최상단의 window 객체는 자바스크립트 코드의 전역 객체이자 브라우저 창(browser window)을 대변하고 이를 제어할 수 있는 메소드(API)를 제공한다.
    - window 객체는 전역 객체이므로 메소드 호출 시 생략할 수 있다.
    - Open
        - window.open(url, name, params) 메소드로 새 창을 열 수 있다.
            - url: 새 창이 로드할 URL이다.
            - name: 새 창의 이름으로 해당 이름을 가진 창이 이미 있으면 그 안에서 열리고, 그렇지 않으면 새 창이 열린다.
            - params: 새 창의 설정을 쉼표로 구분하여 문자열로 전달한다.
            
            ```
            <script>
                document.getElementById('btn1').onclick = () => window.open('http://www.google.com', 'popup1', 'width=1080, height=800');
                document.getElementById('btn2').onclick = () => window.open('http://www.naver.com', 'popup1');
            </script>
            ```
            
    - Alert
        - window.alert(message) 메소드는 확인 버튼을 가지며 메시지를 지정할 수 있는 경고 대화 상자를 띄운다.
            
            ```
            <script>
                        window.alert('alert창 입니다!');
                    </script>
            ```
            
    - Confirm
        - window.confirm(message) 메소드는 확인과 취소 두 버튼을 가지며 메시지를 지정할 수 있는 대화 상자를 띄운다.
        - 반환 값은 확인 버튼을 누를 시 true, 취소를 누르거나 ESC 키를 누르면 false이다.
            
            ```html
            <script>
                    const answer = window.confirm('계속하시겠습니까?');
            
                    if(answer){
                        console.log("확인 버튼");
                    } else {
                        console.log("취소 버튼");
                    }
                </script>
            ```
            
    - Prompt
        - window.prompt(message) 메소드는 사용자가 텍스트를 입력할 수 있도록 안내하는 선택적 메세지를 갖고 있는 대화 상자를 띄운다.
        - 반환 값은 확인을 누를 시 사용자가 입력한 문자열이며, 취소를 누르거나 ESC 키를 누르면 null이다.
            
            ```
            <script>
                const likeNumber = window.prompt("좋아하는 숫자는?");
            
                if(likeNumber) {
                    console.log(`당신이 좋아하는 숫자는 ${likeNumber}이군요.`);
                } else {
                    console.log('값을 입력하지 않으셨군요.');
                }
            </script>
            ```
            
- BOM
    - 브라우저 객체 모델은 문서 이외의 모든 것을 제어하기 위해 브라우저(호스트 환경)가 제공하는 추가 객체를 나타낸다.
    - Location
        - location 객체는 현재 URL을 읽을 수 있게 해주고 새로운 URL로 변경(redirect)할 수 있게 해준다.
            
            ```
            <button id="btn1">새 페이지로 이동하기</button>
            <button id="btn2">새 페이지로 이동하기</button>
            <button id="btn3">새 페이지로 이동하기</button>
            <button id="btn4">새 페이지로 이동하기(뒤로 가기 불가)</button>
            <button id="btn5">서버로부터 현재 페이지 리로드하기</button>
            <button id="btn6">서버로부터 현재 페이지 리로드하기</button>
            <button id="btn7">서버로부터 현재 페이지 리로드하기</button>
            
            <script>
                document.getElementById('btn1').onclick = () => location.assign("https://www.google.com");
                document.getElementById('btn2').onclick = () => location = "https://www.google.com";
                document.getElementById('btn3').onclick = () => location.href = "http://www.google.com";
                document.getElementById('btn4').onclick = () => location.replace("http://www.google.com");  // 뒤로가기
                document.getElementById('btn5').onclick = () => location.reload();
                document.getElementById('btn6').onclick = () => location = location;
                document.getElementById('btn7').onclick = () => location.href = location.href;
            </script>
            ```
            
    - Navigator
        - navigator 객체는 브라우저와 운영체제에 대한 정보를 제공한다.
        - 객체엔 다양한 프로퍼티가 있는데, 가장 잘 알려진 프로퍼티는 현재 사용 중인 브라우저의 정보를 알려주는 navigaotr.userAgent와 브라우저가 실행 중인 운영체제(window, Linux, mAx 등) 정보를 알려주는 navigator.platform이 있다.
            
            ```
            <script>
                for(prop in navigator){
                    console.log(`${prop} : ${navigator[prop]}`);
                }
            
                console.log(navigator.userAgent);               // 실제로는 "Mozilla/version" 기반이 아니었지만 다른 브라우저 벤더들은 자사 제품이 Netscape 브라우저의 특정 버전과 호환된다는 의미로 사용된다.
                console.log(navigator.platform);
            </script>
            ```
            
    - Screen
        - screen 객체는 웹 브라우저 화면이 아닌 운영체제 화면의 속성을 가지는 객체이다.
        - screen.width, screen.height는 화면 너비와 높이를 나타내지만 screen.availwWidth, screen.availHeight는 실제 화면에서 사용 가능한(상태 표시줄 등을 제외한) 너비와 높이를 의미한다.
        - screen.colorDepth는 사용 가능한 색상 수, screen.pixelDepth는 한 픽셀 당 비트 수를 의미한다.
            
            ```
            <script>
                for(prop in screen) {
                    console.log(`${prop} : ${screen[prop]}`);
                }
            </script>
            ```
            
    - History
        - history 객체는 브라우저의 세션 기록, 즉 현재 페이지를 불러온 탭 또는 프레임의 방문 기록을 조작할 수 있는 방법을 제공한다.
        - length는 현재 페이지를 포함해, 세션 기록의 길이를 나타내는 정수 값이며, back 메서드는 뒤로 가기, forward 메서드는 앞으로 가기, go 메서드는 인수로 전달된 값 만큼 이동하는 메서드이다.
            
            ```
            <script>
                for(prop in history){
                    console.log(`${prop} : ${history[prop]}`);
                }
            
                document.getElementById('btn1').onclick = () => history.back();
                document.getElementById('btn1').onclick = () => history.forward();
                document.getElementById('btn3').onclick = function(){
                    let page = document.querySelector('input[name=page]').value;
                    history.go(page);
                }
            </script>
            ```