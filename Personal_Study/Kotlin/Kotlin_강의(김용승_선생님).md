# Kotlin 강의(김용승 선생님)

# Basic

- 코틀린 파일의 특징
    - 코틀린 - 가독성 좋은 짧은 코드 작성을 지향한다. (feat. null에 민감)
    - 자바와 달리 코틀린은 클래스가 반드시 선언 될 필요가 없으며 소스파일명과 클래스명이 달라도 된다.
    - 기본적으로는 package, import, class로 구성되지만 다양한 순서 및 갯수로 구성하고자 하면     따로 정해진 규칙은 없다.
    - main 메소드에 매개변수(소괄호)를 주지 않아도 잘 동작한다.
    - 콘솔에 출력하기 위해서는 print()나 println()을 사용한다.
    - 세미콜론은 적어도 에러는 안나지만, kotlin에서는 사용하지 않는 것이 일반적이고 한 문장에 여러 문장 작성시 주로 사용한다.

# Variable

<aside>
💡 변수를 선언하는 방법

- 선언 형식 : val(var) 변수명[: 타입] = 값
</aside>

- String, Int
    - String, Int
        
        ```kotlin
        var name: String
            name = "홍길동"           // 처음 값이 대입되면 해당 값의 타입으로 고정
            name = "유관순"           // var이므로 같은 타입의 다른 값이 대입될 수 있다.
        //    name = 20              // 다른 타입의 값은 담을 수 없다.
        
            println(name)
        
            var age: Int = 19        // int는 Int로 대문자 사용
        //    age = 20.0             // double과 int 구분
            println(age)
        ```
        
- RawString, StringTemplate
    - RawString, StringTemplate
        
        ```kotlin
        /*
            RawString(원시 문자열)과 StringTemplate(문자열 탬플릿)
        
            일반 문자열은 엔터나 탭 같은 특수기능을 표현하려면 이스케이프 문자('\' 포함한 문자)를 사용해야 하지만
            이와 달리 RawString(""")은 작성한 모습 그대로 활용할 수 있게 해준다.
         */
        fun main(args: Array<String>) {
        
            /* 일반 문자열 사용 */
            var str = "일반 문자열 안에서는 \n\n 문자열 안에 엔터나 스 페 이 스\t 그리고 탭을 자유롭게 사용할 수 " +
                    "\n 있으며 이스케이프 문자 사용도 \n가능하다."
        
            /* RawString 사용 */
            var raw = """원시 문자열 안에서는
        문자열 안에 엔터나 스 페 이 스 그리고 탭을 자유롭게 사용할 수 
        있으며 이스케이프 문자 사용도
        가능하다."""
        
            println(str)
            println(raw)
        
            var num1 = 10
            var num2 = 20
        
            /* 문자열 템플릿: 문자열 안에서 '$'(문자열 탬플릿)을 활용해서 변수의 값을 바로 적용해 볼 수 있다. */
            var startWithNum = "Hello, $num1"
            println(startWithNum)
        
            /* 문자열 템플릿을 활용할 때 $에 {}를 씌우면 표현식(연산결과나 함수의 결과와 같은 하나의 값을 도출하는 식)을 쓸 수 있다. */
            var sum = "sum : ${num1 + num2}"
            println(sum)
        
            var str2 = "\n\t Hello \t\n"
            println(str2)
        
            var trimStr = "trimStr: ${str2.trim()}"
            println(trimStr)
        
        }
        ```
        
- Basic Type
    - Basic Type
        
        ```kotlin
        package com.greedy.variable
        
        /*
            코틀린에서 제공하는 타입의 종류와 저장 가능 크기를 알아보자.
            코틀린의 경우는 정수형(Byte, Short, Int, Long)과 실수형(Float, Double)은 저장 크기를 확인할 수도 있다.
         */
        fun main(args: Array<String>) {
            var score: Int = 100
        
            println("정수형 값의 최소/최대값")
            println("Byte min: ${Byte.MIN_VALUE} max: ${Byte.MAX_VALUE}")
            println("Short min: ${Short.MIN_VALUE} max: ${Short.MAX_VALUE}")
            println("Int min: ${Int.MIN_VALUE} max: ${Int.MAX_VALUE}")
            println("Long min: ${Long.MIN_VALUE} max: ${Long.MAX_VALUE}")
        
            println("실수형 값의 최소/최대값")
            println("Float min: ${Float.MIN_VALUE} max: ${Float.MAX_VALUE}")
            println("Double min: ${Double.MIN_VALUE} max: ${Double.MAX_VALUE}")
        
            println("문자형 값의 최소/최대값")
            println("Char min: ${Char.MIN_VALUE.toInt()} max: ${Char.MAX_VALUE.toInt()}")
        
            println("논리형 값의 최소/최대값")
        //    println("Boolean min: ${Boolean.MIN_VALUE} max: ${Boolean.MAX_VALUE}")          // 에러. 논리형은 저장 크기를 따로 알 수 없다.
        }
        ```
        
- 타입 변환
    
    ```kotlin
    package com.greedy.variable
    
    /*
        타입 변환
        toByte(): Byte 타입으로 변환
        toShort(): Short 타입으로 변환
        toInt(): Int 타입으로 변환
        toLong(): Long 타입으로 변환
        toFloat(): Float 타입으로 변환
        toSDouble(): Double 타입으로 변환
        toChar(): Char 타입으로 변환
    
        코틀린에서는 자바에서와 달리 자동현변환이 적용되지 않으므로 항상 명시적 형변환을 해 줘야 한다.
     */
    
    fun main(args: Array<String>) {
    
        /* 타입을 변환할 변수 선언 */
        var byteValue: Byte = 65
        println(byteValue)
    
        /* Int, SHort, Long, Float, Double 타입으로 각각 변환하는 메소드를 호출 */
        var intValue: Int = byteValue.toInt()
        println(intValue)
    
        var shortValue: Short = byteValue.toShort()
        println(shortValue)
    
        var longValue: Long = byteValue.toLong()
        println(longValue)
    
        var floatValue: Float = byteValue.toFloat()
        println(floatValue)
    
        var doubleValue: Double = byteValue.toDouble()
        println(doubleValue)
    
        /* Char 타입으로 변환. 65는 유니코드 번호로 문자 'A'에 대응되므로 charValue 변수에는 'A'문자 저장 */
        var charValue: Char = byteValue.toChar()
        println(charValue)
    
        /* 문자열을 기본 타입으로 변환 */
        var intString: String = "1234"
        var doubleString: String = "1.234"
    
        var stringToInt: Int = intString.toInt()
        var stringToDouble: Double = doubleString.toDouble()
    
        println(stringToInt)
        println(stringToDouble)
    
        var booleanString: String = "true"
        var stringToBoolean: Boolean = booleanString.toBoolean()
        println(stringToBoolean)
    
        /* 기본 타입을 문자열로 변환 */
        var num: Int = 123
        var intToString: String = num.toString()
        println(intToString)
    
    }
    ```
    
- Any 타입
    - 모든 타입의 값을 대입할 수 있는 최상위 타입
    - Any 타입 변수에는 초기화 이후에도 어떤 종류의 값이든 대입이 가능
        
        ```kotlin
        /* Any 타입 변수 선언 */
          var anyValue: Any
        
          /* 대입 후 타입이 정해짐 */
          anyValue = 100;
        
          /* ::class.simpleName으로 코틀린의 타입을 확인할 수 있다. */
          println(anyValue::class.simpleName)
        
          /* 문자열 대입 */
          anyValue = "Hello"
          println(anyValue::class.simpleName)
        
          /* 실수형 숫자 대입 */
          anyValue = 1.234
          println(anyValue::class.simpleName)
        ```
        
- Nullable 타입
    - 자바의 경우 null이 대입 되는 참조자료형이나 Wrapper 클래스 같은 경우에는 null값 여부를 확인하는 과정을 생략, 혹은 변수에 null값이 대입 됐을 수도 있다는 상황을 가정하지 않고 코드를 작성해서 NullPointerException이 발생하는 상황이 많다.
    - 코틀린은 변수의 타입을 기본적으로 null값 대입이 허용되지 않는 타입으로 선언하게 되어 있고 그 외에 여러 연산자를 통해 NPE를 방지하고 있다.
        
        ```kotlin
        /* 기본적으로 null값 대입을 허용하지 않는 타입(Int)으로 선언 */
            var num = 100
        
            /* null 값 대입 불가 */
        		//    num = null
        
            /* null을 대입받을 수 있는 Int? 타입의 변수를 선언 */
            var a: Int? = 100
            a = null
            println(a)
        
            var s: String = "Hello"
        		//    s = null
        
            println(s.length)       // NPE(NullPointerException) 걱정 없이 안전하게 접근 가능
        ```
        
    - let 함수를 이용한 null 타입값 접근
        - let 함수는 범위함수로써 변수의 값이 null이 아닌 경우에 실행 할 코드 블록을 작성할 수 있게 도와주는 함수
        - it: value-parameter인 c가 null이 아닌 경우 c를 의미한다.
            
            ```kotlin
            c?.let {
              println("let을 통한 null이 아닌 값 확인: " + it)     // it => c
            }
            ```
            
    - 안전호출연산자(Safe-call Operator)를 이용하는 방법
        - ?와 .을 붙여 null일 경우 우측에 접근하지 않고 곧바로 null값을 반환한다.
            
            ```kotlin
            var len: Int? = c?.length       // c가 null일 때 프로퍼티, 메소드 접근을 무시 (NPE 방지)
            
            	println("안전호출 연산자를 통한 문자열 길이 확인: $len")
            ```
            
    - 엘비스(Elvis)연산자를 이용하는 방법
        - 연산자 왼쪽의 피연산자가 null이 아닐 경우 해당 값을 반환하고 null일 경우 오른쪽 피연산자를 반환한다.
            
            ```kotlin
            var one = null ?: 1      // 왼쪽 피연산자의 값이 null이므로 오른쪽 피연산자값(1)을 반환
              println(one)
            
            var two = 2 ?: 1         // 왼쪽 피연산자의 값이 null이 아니므로 왼쪽 피연산자값(2)을 반환
              println(two)
            ```
            
    - null값이 아님을 보증하는 연산자를 사용해 null을 허용하지 않는 타입으로 변환
        - !! 연산자를 이용하여 타입을 null이 불가능한 타입이라고 명명할 수는 있지만, 컴파일 에러를 런타입 Exception으로 바꾼 것 뿐, NPE는 그대로 발생한다. 즉, 가독성을 위한 연산자
            
            ```kotlin
            var nullableStr: String? = null
              var str: String = nullableStr!!
            //  println(str.length)
            ```
            
    - null을 반환하는 타입 변환 메소드
        - toIntOrNull() 메소드를 이용, 타입 변환시 NPE가 발생하면 NPE가 아닌 NULL을 반환한다.
            
            ```kotlin
            var wrongNumberString = "숫자아님"
            
              var wrong: Int? = wrongNumberString.toIntOrNull()
              println(wrong)
            ```
            
- 배열
    - 같은 타입의 값을 여러 개 저장하고 관리하기 위해 사용(Any 타입 사용 시에는 여러 타입도 가능)
    - 배열을 사용하는 방법
        - 한가지 배열의 초기값과 크기를 지정 후 생성(Array)
            
            ```kotlin
            /* Int형만 저장하는 크기 3에 초기값 1로 채워진 배열 선언 */
              var arr1: Array<Int> = Array<Int>(3){1}
              println(arr1[0])
              println(arr1[1])
              println(arr1[2])
            
              /* 모든 자료형을 받아줄 수 있는 크기 2에 초기값 1로 채워진 배열 선언 */
              var arr2: Array<Any> = Array<Any>(2){1}
              arr2[1] = "Hello"
              println(arr2[0])
              println(arr2[1])
            
              // 모든 자료형을 받아줄 수 있는 크기 2에 각각 다른 자료형의 값들이 채워진 배열 선언 */
              var arr3: Array<Any> = arrayOf(1.234, "Hello")
              println(arr3[0])
              println(arr3[1])
            ```
            
        - 원시 타입값을 저장하는 배열 생성(기본자료형arrayOf)
            
            ```kotlin
            var intArr = intArrayOf(1, 2, 3)
            //    var intArr = intArrayOf(1, 2, null)     // 원시 타입 배열이므로 null은 저장 안됨
              println(intArr[0])
              println(intArr.javaClass.canonicalName)         // 자바에서의 타입 확인 (int[])
            
              var charArr = charArrayOf('a', 'b', 'c')
              println(charArr.javaClass.canonicalName)        // char[]
            ```
            
        - 래퍼 타입값을 저장하는 배열 생성(arrayOf)
            
            ```kotlin
            var intWrapperArr = arrayOf(1, 2, 3)
              println(intWrapperArr.javaClass.canonicalName)  // java.lang.Integer[]
            ```
            
        
- 상수와 읽기 전용 변수
    
    ```kotlin
    package com.greedy.variable
    
    /* 상수(const val)와 읽기 전용 변수(val) */
    class Student {}
    
    const val PI = 3.1415       // 전역 변수로 선언 가능
    
    val lecture = "kotlin"      // 전역 변수로 선언 가능
    
    //const val student: Student = Student()      // 기본자료형과 String 형을 제외한 참조 자료형으로 선언 불가능
    fun main(args: Array<String>) {
    
        /* 1. 지역 변수는 읽기 전용 변수(val)만 가능하다. */
        /* const val */
    //    const val PI = 3.1415  // 지역 변수로는 선언 불가능
    //    PI = 3.14              // 전역 변수로 선언된 상수는 수정 불가능
        println((PI))
    
        /* val */
        val lecture2 = "android"    // val은 지역변수로 선언 가능
    //    lecture2 = "java"         // 읽기 전용 변수이므로 수정 불가능 (읽기만 가능)
    
        /* 2. 기본자료형이나 String을 제외한 자료형으로 변수를 선언하려면 읽기 전용 변수(val)만 가능하다. */
        val student: Student = Student()    // 생성자를 활용해 객체를 생성할 때 new를 사용하지 않는다.
        println(student)
    
    }
    ```
    

# Operator

- 산술 연산자(mathmetical operator)
    
    ```kotlin
    var sum = 5 + 8
    var sub = 4 - 6
    var mul = 3 * 5
    
    println("sum: $sum")
    println("sub: $sub")
    println("mul: $mul")
    
    var div = 6 / 5
    var divDouble1 = 6.0 / 5.0
    var divDouble2 = 6 / 5.toDouble()
    
    println("div: $div")
    println("divDouble1: $divDouble1")
    println("divDouble2: $divDouble2")
    
    var mod1 = 6 % 5
    var mod2 = 3 % 3
    
    println("mod1: $mod1, mod2: $mod2")
    
    var complex = ((5 + 3) * (4 / 2)) % 3
    println("complex: $complex")
    
    var intValue1: Int = 2147483647
    println("before overflow: $intValue1")
    
    intValue1 = intValue1 + 1
    println("after overflow: $intValue1")
    
    intValue1 = intValue1 - 1
    println("after underflow: $intValue1")
    ```
    
- 복합 대입 연산자(augmented assignment operator)
    
    ```kotlin
    var w1 = 10
    
    /* w1 = w1 + 20 대입문과 같은 결과 */
    w1 += 20
    println("w1 += 20: $w1")
    
    /* w1 = w1 - 10 대입문과 같은 결과 */
    w1 -= 10
    println("w1 -= 10: $w1")
    
    /* w1 = w1 * 2 대입문과 같은 결과 */
    w1 *= 2
    println("w1 *= 2: $w1")
    
    /* w1 = w1 / 2 대입문과 같은 결과 */
    w1 /= 2
    println("w1 /= 2: $w1")
    
    /* w1 = w1 % 3 대입문과 같은 결과 */
    w1 %= 3
    println("w1 %= 3: $w1")
    ```
    
- 증감 연산자
    
    ```kotlin
    /* 값을 1증가(value += 1과 같은 결과) */
    value++
    
    /* 값을 1증가시켰으므로 101을 출력 */
    println("after value++: " + value)
    
    /* 값을 1감소(value -= 1과 같은 결과) */
    value--
    
    /* 값을 1갑소시켰으므로 100을 출력 */
    println("after value--: " + value)
    
    /* -연산자를 이용한 부호 변경 */
    println("-value: " + (-value))
    
    /* -연산자를 두 번 적용해 값의 부호를 원래대로 변경 */
    println("-(-value)): " + (-(-value)))
    ```
    
- 비교 연산자
    
    ```kotlin
    /* 두 값이 같은지 여부를 판단하고 있으므로 참(true)을 반환 */
    var r1 = (1 == 1)
    
    /* 두 값이 같지 않은지 여부를 판단하고 있으므로 거짓(false)를 반환 */
    var r2 = (1 != 2)
    
    /* 문자열의 값이 서로 일치하는지 여부를 판단하고 있으므로 참(true)을 반환 */
    var r3 = ("Hello" == "Hello")   // 자바와 달리 ==으로 문자열 동등 비교 가능
    
    /* 문자열의 길이가 일치하는지 여부를 판단하고 있으므로 참(true)을 반환 */
    var r4 = ("Hello".length == "World".length)
    
    /* 값의 대소 비교 */
    var r5 = 2 > 1
    var r6 = 2 >= 2
    
    println("(1 == 1): $r1")
    println("(1 != 1): $r2")
    println(""""Hello" == "Hello": $r3""")
    println(""""Hello".length == "Hello".length: $r4""")
    println("(2 > 1): $r5")
    println("(2 >= 2): $r6")
    ```
    
- 논리 연산자
    
    ```kotlin
    /* 논리 연산자 (logical operator) */
    /* AND 연산자 사용 */
    /* 두 값이 모두 참이므로 true를 반환 */
    var r7 = true && true
    /* 두 값 중 하나가 거짓이므로 false를 반환 */
    var r8 = true && false
    
    /* OR 연산자 사용 */
    /* 두 값이 모두 거짓이므로 false를 반환 */
    var r9 = false || false
    /* 두 값 중 하나가 참이므로 true를 반환 */
    var r10 = false || true
    
    /* 부정 연산자 사용 */
    /* 참 값을 거짓 값으로 변환 */
    var r11 = !true
    /* 거짓 값을 참 값으로 변환 */
    var r12 = !false
    
    println("true && true : $r7")
    println("true && false : $r8")
    println("false || false : $r9")
    println("false || true : $r10")
    println("!true : $r11")
    println("!false : $r12")
    
    /* 비교 연산자와 논리 연산자가 포함된 수식 */
    var result = ((2 > 1) && ("Hello" != "World"))
    println("$result")
    ```
    
- 인덱스 연산자
    
    ```kotlin
    var nums = arrayOf(100, 101, 102)
    
    println("nums[0]: ${nums[0]}")
    println("nums[1]: ${nums[1]}")
    println("nums[2]: ${nums[2]}")
    
    nums[1] = 200
    println(nums[1])
    ```
    
- mutableMap
    
    ```kotlin
    var areaCodeMap = mutableMapOf("02" to "서울", "055" to "경남")
    println(areaCodeMap["02"])
    println(areaCodeMap["055"])
    /*
        Java의
        Map<String, String> areaMap = new Map<>();
        areaMap.put("02", "서울")
        areaMap.put("055", "경남")
     */
    
    areaCodeMap["051"] = "부산"
    println(areaCodeMap["051"])
    ```
    
- in 연산자
    - 배열, 리스트, 집합 혹은 Range 객체(범위 객체)에 특정 원소가 포함되어 있는지 검사하기 위해 사용한다.
    - 배열 요소 포함 여부 검사
        
        ```kotlin
        var arr = arrayOf(1, 2, 3, 4, 5)
        
        println("3 in arr: " + (3 in arr))
        println("6 in arr: " + (6 in arr))
        ```
        
    - 리스트 요소 포함 여부 검사
        
        ```kotlin
        var list = listOf('a', 'b', 'c', 'd', 'e')
        
        println("'a' in list: ${'a' in list}")
        println("'f' in list: ${'f' in list}")
        ```
        
    - 범위 객체 요소 포함 여부 검사
        
        ```kotlin
        println(1 in 1..10)
        
        var a = 9
        println(a in 1..9)
        
        println('a' in 'a'..'z')
        ```
        
        - 반복문에서 범위 객체 사용
            
            ```kotlin
            for(num in 1..5){       // in 연산자를 통해 우항의 값을 좌항에 각각 대입 시킴
                println("$num")
            }
            println()
            
            var items = arrayOf('a', 'b', 'c')
            for(it in items) {
                println(it)
            }
            println()
            
            /* !를 사용해서 in 연산자를 부정할 수도 있다. */
            println(1 !in 1..10)
            ```
            
- 연산자 우선순위
    
    ```kotlin
    (우선순위 높음)
    	후위 연산자                          ++, --, ., ?., ?
    	전위 연산자                          -, +, ++, --, !, label
    	타입 변환 연산자                      :, as, as?
    	곱셈, 나눗셈, 나머지 연산자            *, /, %
    	덧셈, 뺄셈 연산자                     +, -
    	범위 연산자                          ..
    	중위 함수                            N/A
    	엘비스 연산자                         ?:
    	포함 관계 여부 및 타입 확인 연산자       in, !in, is, !is
    	대소 비교 연산자                       <, >, <=, >=
    	동등 비교 연산자                       ==, !==
    	AND 연산자                           &&
    	OR 연산자                            ||
    	스프레드 연산자                        *
    	대입, 복합 대입 연산자                  =, +=, -=, *=, /=, %=
    (우선순위 낮음)
    ```
    

# Flow Control

- if-else
    - if - else
        
        ```kotlin
        if(true) {
            println("true")
        }
        
        if(100 > 1) {
            println("100은 1보다 크다")
        }
        
        var a = 100
        if(a >= 100) {
            println("a는 100보다 크거나 같다.")
        }
        
        var age = 20
        var gender = "남성"
        if(age >= 20 && gender == "남성"){
            println("성인 남성입니다.")
        }
        
        var num = 10
        if(num % 2 == 0) {
            println("짝수입니다.")
        } else {
            println("홀수입니다.")
        }
        ```
        
    - until
        - ~까지(미만)을 의미한다.
            
            ```kotlin
            var score = 70
            
            var grade: String? = null
            if(score == 100 || score in 90 until 100) {
                grade = "A"
            } else if(score in 80 until 90) {
                grade = "B"
            }
            
            println("grade: ${grade ?: "F"}")
            ```
            
    - 조건문을 표현식으로 사용
        
        ```kotlin
        var num1 = 10
        var num2 = 20
        
        var bigger = if(num1 >= num2) num1 else num2
        
        println(bigger)
        
        var score = 75
        
        var grade = if(score == 100 || score in 90 until 100) {
            println("Perfect")
            "A"
        } else if(score in 80 until 90) {
            println("Excellent")
            "B"
        } else if(score in 70 until 80) {
            println("Good")
            "ABCD".get(2)         // get은 java에서의 charAt과 같다.
        } else {
            println("Average")
            "D"
        }
        
        println("grade: $grade")
        ```
        
- when
    1. 조건에 함수 반환값이나 직접 연산을 이용할 수도 있다.
        
        ```kotlin
        var num = 2
        when(num) {                             // 자바 switch와 유사, 단 case에 범위 가능
            1 -> println("One")                 // case 생략
            (1.inc()) -> println("Two")         // ,inc() => ++
            (1 * 3) -> println("Three")         // 1 * 3 => 1 ~ 3
            else -> println("No match")         // else => default
        }
        
        when(num) {
        
            1 -> {
                println("Hello")
                println(num)
            }
            2 -> {
                println("World")
                println(num)
            }
        }
        ```
        
    2. 조건 처리한 결과를 반환 받을 수도 있다.
        
        ```kotlin
        var a = 10.0
        var b = 20.0
        var op = '+'
        var result: Double? = when(op) {
            '+' -> a + b
            '-' -> a - b
            '*' -> a * b
            '/' -> a / b
            else -> null
        }
        println(result)
        ```
        
    3. 자료형에 따라 다르게 처리할 수 있다.
        
        ```kotlin
        var x: Any = "Hello"
            
        when(x) {
            is String -> println(x.length)      // is: 타입 확인 연산자
            is Int -> println(x * 2)
            is Double -> println(x.toInt())
            else -> println("No Match")
        }
        ```
        
    4. 범위객체를 사용하여 범위로 검색이 가능하다.
        
        ```kotlin
        var score = 81
        var scoreResult = when(score) {
            in 91..100 -> "A"
            in 81..90 -> "B"
            in 71..80 -> "C"
            else -> "D"
        }
        println(scoreResult)
        ```
        
- while, do~while
    
    ```kotlin
    var a = 1
    while(a <= 10) {
        println(a)
        a++
    }
    
    var b = 1
    do{
        println(b)
        b++
    } while(b <= 10)
    ```
    
- for문
    - 범위 객체 활용
        
        ```kotlin
        /* 범위객체(1부터 10까지) */
        var range = 1..10
        println("range: $range")
        println("range.first: ${range.first}")
        println("range.last: ${range.last}")
        
        /* 1부터 10까지 for문을 통한 출력 */
        for(i in 1..10){
            println(i)
        }
        ```
        
    - until 사용
        
        ```kotlin
        var oneToNine = 1 until 10
        println("oneToNine: $oneToNine")
        println("oneToNine.first: ${oneToNine.first}")
        println("oneToNine.start: ${oneToNine.start}")
        println("oneToNine.last: ${oneToNine.last}")
        
        for(i in oneToNine) {
            println(i)
        }
        ```
        
    - 배열의 반복
        
        ```kotlin
        var arr = arrayOf(1, 2, 3)
        for(i in 0..(arr.size - 1)) {   // 코틀린에서는 배열의 크기가 size이다.
            println("..연산자: " + arr[i])
        }
        
        for(i in 0 until arr.size) {   // 코틀린에서는 배열의 크기가 size이다.
            println("until 연산자: " + arr[i])
        }
        ```
        
    - 역순으로 범위 객체 생성(downTo)
        
        ```kotlin
        var tenToOne = (1..10).reversed()
        for(i in tenToOne) {
            println(i)
        }
        
        var oneToTenStepTwo = 1..10 step 2
        for(i in oneToTenStepTwo) {
            println(i)
        }
        ```
        
    - 기타 활용
        
        ```kotlin
        /* 10부터 2씩 감소하되 1보다 큰 범위 객체 생성 */
        var tenToOneStepTwo = 10.downTo(1).step(2)
        for(i in tenToOneStepTwo) {
            println(i)
        }
        
        /* 문자 범위를 생성하는 것도 가능 */
        var aToZ = 'a'..'z'
        for(c in aToZ) {
            print(c + " ")
        }
        println()
        
        var gaToNa = '가' until '나'
        for(c in gaToNa) {
            print(c + " ")
        }
        ```
        
- 중첩 반복문(nested for)
    
    ```kotlin
    println("구구단 출력")
    for(i in 2 .. 9){
        for(j in 1 .. 9){
            println("$i x $j = ${i * j}")
        }
    }
    
    println("별찍기 출력")
    val row = 5
    for(i in 1 .. row){
        for(j in 1 .. i){
            print("* ")
        }
        println()
    }
    ```
    
- Collection 객체를 활용한 for문
    - Array 객체를 사용하여 반복
        
        ```kotlin
        var arr = arrayOf(1, 2, 3)
            
        for(i in arr) {
            println(i)
        }
        ```
        
    - list 객체를 순회
        
        ```kotlin
        var list = listOf(1, 2, 3)
        for(item in list) {
            println(item)
        }
        ```
        
    - map 객체를 순회
        
        ```kotlin
        var map = mapOf("a" to 1, "b" to 2, "c" to 3)
        for(pair in map) {
            println("${pair.key} to ${pair.value}")
        }
        ```
        
    - set 객체 순회
        
        ```kotlin
        var set = setOf("a", "b", "c")
        for(ele in set) {
            println(ele)
        }
        ```
        
- 제어문
    - break
        
        ```kotlin
        while(true) {
            print("'b'를 입력하여 break 명령어를 실행 : ")
            var input = readLine()?.trim()
            if(input != "") {
                if(input == "b") {
                    println("break 명령어를 이용하여 반복문을 탈출합니다.")
                    break
                } else {
                    println("${input}을 입력하셨습니다.")
                }
            } else {
                println("입력값 없음")
            }
        }
        ```
        
    - cotinue
        
        ```kotlin
        for(i in 2 .. 9) {
            if(i % 2 != 0) continue
            for(j in 1 .. 9) {
                println("$i x $j = ${i * j}")
            }
        }
        ```
        

# Function

- 코틀린 함수의 종류
    - 매개변수 및 반환형이 없는 함수
        
        ```kotlin
        fun printHello(){
        }
        ```
        
    - 매개변수 없고 반환형 있는 함수
        
        ```kotlin
        fun getHello(): String {
            return "Hello"
        }
        ```
        
    - 매개변수 있고 반환값도 있는 함수
        
        ```kotlin
        fun sum(a: Int, b: Int): Int {
            return a + b
        }
        ```
        
    - 표현식을 활용한 함수 정의 기능
        
        ```kotlin
        fun sum(a: Int, b: Int): Int = a + b
        fun sum(a: Int, b: Int) = a + b         // 표현식의 결과를 통해 반환형 유추가 가능하면 반환형 생략 가능
        ```
        
    - 조건문을 표현식으로 활용한 함수 정의
        
        ```kotlin
        fun getGrade(score: Int) = when(score) {
          in 91..100 -> "A"
          in 81..90  -> "B"
          in 71..80 -> "C"
          else -> "D"
        }
        
        fun getBigger(a: Int, b: Int): Int = if(a > b) a else b
        ```
        
    - 함수 호출을 표현식으로 활용한 함수 정의
        
        ```kotlin
        fun removeAllSpace(target: String): String = target.replace(" ", "")
        ```
        
    - 모든 인자값에 기본값이 적용된 함수
        
        ```kotlin
        fun sumWithDefault(a: Int = 10, b: Int = 20): Int {
          return a + b
        }
        ```
        
    - 일부 인자값에 기본값이 적용된 함수
        
        ```kotlin
        fun sumWithDefault2(a: Int, b: Int = 20): Int {
          return a + b
        }
        ```
        
    - 가변인자가 적용된 함수
        
        ```kotlin
        fun sumWithVargs(vararg nums: Int): Int {
          var total = 0
          for(num in nums) {
              total += num
          }
          return total
        }
        ```
        
    - 일반 인자와 가변인자가 섞여 있는 경우(가변인자는 항상 가장 마지막에 선언해야 한다.)
        
        ```kotlin
        fun sumWithVargsWithBase(base: Int, vararg nums: Int): Int {
          var total = base
          for(num in nums){
              total += num
          }
          return total
        }
        ```
        
- 명명인자(Named Argument) 활용 함수
    - 코틀린에서는 인자값의 개수가 많다면 일일이 전달해야 할 인자값의 순서와 타입을 기억하는 불편함을 해소하기 위해서 매개변수의 이름과 인자값을 동시에 전달하는 형태로 함수를 호출할 수 있다.
        
        ```kotlin
        fun main(args: Array<String>) {
        
          callTo("이순신", "유관순", 2)
          callTo(times = 2, from = "이순신", to = "유관순")
          callTo("이순신", times = 2, to = "유관순")
        }
        
        fun callTo(from: String, to: String, times: Int){
          println("${from}씨가 ${to}를 ${times}번 눌렀습니다.")
        }
        ```
        
- 로컬 함수
    - 복잡한 작업을 수행하는 함수의 기능을 내부적으로 분할한 후 구현할 때 사용할 수 있다.
    - 함수 내부에 있으므로 외부에서는 사용할 수 없다.
        
        ```kotlin
        fun main(args: Array<String>) {
            var result = outerFunc("to")
        //    var result2 = localFunc("to")   // outerFunc이 아닌 mian 함수(외부의 다른 함수)에서는 호출 불가
            println(result)
        }
        
        fun outerFunc(target: String): String {
            fun localFunc(str: String): String {
                return "Hello from local $str"
            }
            return localFunc(target) + " outer"
        }
        ```
        
- 람다 함수
    - 람다식이란 익명함수(이름 없는 함수)의 형태로 화살표 표기법을 사용한다.
    - 구성 :
        - val 함수처럼 사용 할 변수: 람다식의 선언 자료형 -> 람다식의 반환 자료형 { 람다식의 매개변수 -> 람다식의 처리 내용 }
        - 전체 모습
            
            <aside>
            💡 val plus: (Int, Int) -> Int = { x: Int, y: Int -> x * y }
            
            </aside>
            
        - :로 선언된 선언 자료형 생략
            
            <aside>
            💡 val plus = { x: Int, y: Int -> x * y}
            
            </aside>
            
        - :이후 선언 자료형 생략
            
            <aside>
            💡 val plus: (Int, Int) -> Int = { x, y -> x * y }
            
            </aside>
            
    - 매개변수 있고 반환형도 있는 람다식
        
        ```kotlin
        val square: (Int) -> Int = { number: Int -> number * number }
        val square = { number: Int -> number * number }
        println(square(4))
        ```
        
    - 매개변수 없고 반환형도 없는 람다식
        
        ```kotlin
        var printHello: () -> Unit = {println("hello")}
        var printHello = {println("hello")}
        printHello()
        ```
        
    - it을 활용한 축약형(매개변수가 하나일 때 주로 사용)
        
        ```kotlin
        var sayHelloTo: (String) -> Unit = { println("say hello to $it") }
        sayHelloTo("김철수")
        ```
        

# Class

- 클래스 생성과 접근
    
    ```kotlin
    package com.keaunsol.classes
    
    /* 자동차 클래스 정의 */
    /*
        클래스 내부에 정의한 변수 혹은 상수를 속성(property)라고 하며
        함수는 메소드(method)라고 한다.
     */
    class Car {
    
        /* 속성 정의 */
        var speed: Int = 0                  // 현재 속도
        var isOn: Boolean = true            // 차량 시동 여부
    
        /* 메소드 정의 */
        fun accelerate() {                  // 가속
            speed += 10
        }
    
        fun decelerate() {                  // 감속
            speed -= 10
            if(speed < 0) speed = 0
        }
    
        fun turnOn() {                      // 시동 On
            isOn = true
        }
    
        fun turnOff() {                     // 시동 Off
            isOn = false
            speed = 0;
        }
    
        fun speedToString(): String {       // 현재 속도값 반환
            return "current speed: $speed"
        }
    }
    
    fun main(args: Array<String>) {
    
        var c = Car()                       // Car 객체 생성(기본생성자를 활용)
    
        /* 시동을 켠다. */
        c.turnOn()
    
        /* 시동 여부 및 속도 확인 */
        println(c.isOn)
        println(c.speed)
    
        /* 가속을 한다. */
        c.accelerate()
        c.accelerate()
    
        /* 가속 후 현재 속도 확인 */
        println(c.speedToString())
    
        /* 감속을 한다. */
        c.decelerate()
    
        /* 감속 후 현재 속도 확인 */
        println(c.speedToString())
    
        /* 시동을 끈다. */
        c.turnOff()
    
        /* 시동 여부 및 속도 확인 */
        println(c.isOn)
        println(c.speed)
    }
    ```
    
- 주 생성자를 통한 클래스 생성
    - 생성자가 없는 클래스
        
        ```kotlin
        class Person1 {
            var name: String = "홍길동"
            var age: Int = 10
        }
        
        var p1 = Person1()
        println("${p1.name}, ${p1.age}")
        ```
        
    - 주 생성자가 포함된 클래스
        
        ```kotlin
        class Person2(val name: String, var age: Int, val gender: String) {
        
        }
        
        var p2 = Person2("홍길동", 20, "남자")
        var p2 = Person2(age = 20, gender = "남성", name = "홍길동") // 명명인자 사용 가능
        
        p2.age = 30
        //    p2.name = "테스트"             // val 변수는 수정 불가(읽기 전용이므로)
        
        println("${p2.name}, ${p2.age}, ${p2.gender}")
        ```
        
    - 주 생성자를 통해서 값만 전달 받는 클래스
        
        ```kotlin
        class Person3(name: String, age: Int, gender: String) {
            var name: String = name
            var age: Int = age
            var gender: String = gender
        }
        
        var p3 = Person3("홍길동", 20, "남자")
        println("${p3.name}, ${p3.age}, ${p3.gender}")
        ```
        
    - 주 생성자에 기본값이 설정된 클래스
        
        ```kotlin
        class Person4(val gender: String, val name: String = "유관순", var age: Int = 20){
        
        }
        var p4 = Person4("여성")
        var p4 = Person4("남성", age= 40, name = "임꺽정")
        println("${p4.name}, ${p4.age}, ${p4.gender}")
        ```
        
- 보조 생성자
    
    ```kotlin
    package com.keaunsol.classes
    
    /* 보조 생성자(자바에서의 생성자 같은 개념) */
    /*
       this()의 의미는 주 생성자 또는 다른 보조 생성자를 호출하는 의미를 지니게 된다.
       어떤 보조 생성자이든 반드시 주 생성자를 호출해야 한다.
     */
    class MyClass(name: String) {       // 주 생성자
        var name: String = name
    
        constructor(arg: Int): this("홍길동") {        // 보조 생성자
            println("매개변수 1개 있는 보조 생성자만 호출: $arg") 
            // 주 생성자 매개변수 name으로 보조 생성자 생성
        }
    
        constructor(arg1: Int, arg2: Int): this(arg1) {     // 보조 생성자
            println("매개변수 2개 있는 보조 생성자 호출: $arg1, $arg2")
            // 주생성자 매개변수 name, 보조 생성자1의 매개변수 name으로 보조 생성자 생성
        }
    }
    
    fun main(args: Array<String>) {
    //    var my1 = MyClass()
    //    var my2 = MyClass(123)
        var my3 = MyClass(111, 222)     // 주 생성자 호출
    }
    ```
    
- 추가 보조 생성자
    
    ```kotlin
    package com.keaunsol.classes
    
    /* 추가 보조 생성자 정의 */
    class Person5(var name: String, var age: Int, val gender: String){
    
        var job: String = "Unkonwn"
        var salary: Int? = null
    
        // 주 생성자로 보조 생성자 생성
        constructor(name: String, age: Int, gender: String, job: String): this(name, age, gender) {
            println("기본적으로 이름, 나이, 성별을 초기화하고 직업까지 정의하는 생성자 호출")
            this.job = job
        }
    
        constructor(name: String, age: Int, gender: String, job: String, salary: Int): this(name, age, gender, job) {
            println("기본적으로 이름, 나이, 성별, 직업을 초기화하고 급여까지 정의하는 생성자 호출")
            this.salary = salary
        }
    
    }
    
    fun main(args: Array<String>) {
        var p5 = Person5("김철수", 20, "남성")
        println("${p5.name}, ${p5.age}, ${p5.gender}, ${p5.job}, ${p5.salary}")
    
        p5 = Person5("이영희", 30, "여성", "의사")
        println("${p5.name}, ${p5.age}, ${p5.gender}, ${p5.job}, ${p5.salary}")
    
        p5 = Person5("김영희", 40, "남성", "프로그래머", 4000000)
        println("${p5.name}, ${p5.age}, ${p5.gender}, ${p5.job}, ${p5.salary}")
    }
    ```
    
- 접근 제어자
    - private => 해당 파일 또는 클래스에서만 사용 가능
    - protected => 상속받은 자식 클래스 및 인터페이스에서만 사용 가능
    - internal => 같은 모듈이면 어디서든 사용 가능(같은 프로그램에서만 어디서든 사용 가능)
    - public => 어디서든 사용가능
    - 부모 클래스
        
        ```kotlin
        // open 키워드를 써서 부모 클래스임을 명시
        open class PublicClass(var publicProp1: String, private var privateProp1: String) {   
        
            var publicProp2 = "public Prop2"
            private var privateProp2 = "private Prop2"
        
            protected var protectedProp = "protected"
        
        		// 자신의 클래스에서 정의한 속성들은 접근제어자와 상관없이 접근 가능함
            fun publicFunc() {          
                println(publicProp1)
                println(privateProp1)
                println(protectedProp)
            }
        
            private fun privateFunc() = println("private 메소드")
        
            protected fun protectedFunc() = println("자식 클래스")
        }
        
        private class PrivateClass() {
        
        }
        
        fun main(args: Array<String>) {
            val publicClass = PublicClass("Hello", "Kotlin")
            publicClass.publicFunc()
        //    publicClass.privateProp2          // private에 의해 접근 불가
        //    publicClass.privateFunc()         // private에 의해 접근 불가
        
        		// 클래스에 붙은 private는 같은 파일 안에서 접근 가능
            val privateClass = PrivateClass()   
        
            publicClass.publicFunc()
        
        }
        
        /* 함수를 정의하며 접근 제어자 설정 */
        fun publicFunc() = println("public function")
        
        /* private 접근 제어자를 붙인 함수는 같은 파일 내부에서만 사용이 가능하다. */
        private fun privateFunc() = println("private function")
        
        /* 변수를 정의하며 접근 제어자 설정 */
        var publicVariable = "public variable"
        
        /* private 접근 제어자를 붙인 변수는 같은 파일 내부에서만 사용이 가능하다. */
        private val privateConstant = "private constant"
        ```
        
    - 자식 클래스에서 부모 클래스 접근
        
        ```kotlin
        class ChildClass(prop1: String, prop2: String) : PublicClass(prop1, prop2) {
            fun parentAccess() {
                println(protectedProp)      // protected 필드는 자식 클래스에서 접근 가능하다.
        //        println(privateProp1)
                protectedFunc()             // protected 메소드도 자식 클래스에서 접근 가능하다.
        //        privateFunc()
            }
        }
        
        fun main(args: Array<String>) {
            var pClass = PublicClass("Hi", "Everyone")
        //    pClass.protectedFunc()          // 자식 클래스가 아닌 곳에서는 protected 속성 및 메소드에 접근 불가능하다.
        //    pClass.protectedProp
        
            publicFunc()
        //    privateFunc()               // 같은 파일 아니므로 에러
            println(publicVariable)
        //    println(privateConstant)    // 같은 파일 아니므로 에러
        }
        ```
        
- Setter, Getter
    - 코틀린은 프로퍼티를 선언하면 setter/getter가 자동으로 선언되고 재정의 할 수도 있다.
        
        ```kotlin
        class GetterAndSetterClass() {
            var num: Int = 0
        
                /* setter는 속성 값을 설정하기 위해 값의 대입이 이뤄지는 시점에 자동 호출 됨 */
                private set(value) {            // 외부에서의 값의 오염을 방지하고자 setter에는 private 적용이 가능하다.
                    println("값을 ${value}로 설정")
                    field = value               // field는 백킹 필드(내부적으로 setter가 동작할 때 재귀호출을 막기 위함 )
                }
        
                /* getter는 해당 속성 값을 읽어오는 시점에 호출 됨 */
                get() {                         // getter는 private 적용이 안 된다.
                    println("${field}값을 반환")
                    return field
                }
        }
        
        var gas = GetterAndSetterClass()
        gas.num = 100                       // num의 setter를 자동 호출
        println(gas.num)                    // num의 getter를 자동 호출
        ```
        
    - val과 var로 getter만 생성할 지 둘 다 생성할 지 정할 수 있다.
    - var이지만 setter를 private로 만들어 외부에서 변경할 수 없도록 할 수 있다.
    - 프로퍼티에 get()과 set()함수를 정의함으로써 자동으로 생성된 getter/setter를 재정의할 수 있다.
    - val인 필드는 getter만 만들어지고 var인 필드는 setter도 만들어진다.
        
        ```kotlin
        class Person6(val name: String, pAge: Int) {
            var age: Int = 0
                set(value) {
                    /* setter에서 when을 활용한 조건식 사용 가능, 이후 field에 반영 */
                    when {
                        value < 0 -> throw Exception("음수 나이는 허용되지 않습니다.")
                        value > 200 -> throw Exception("나이가 너무 많습니다.")
                    }
                    field = value
                }
        
            var isMinor = pAge < 21
                get() = this.age < 20
        
            /* init 블록을 이용해서 속성값 초기화 */
            /*
                init 블록은 주 생성자를 통한 초기화 작업(대입작업)이 끝난 직후에 실행된다.
                속성값을 초기화 할 때 필요한 코드가 있다면 init블록을 사용하게 된다.
             */
            init {
                age = pAge - 1
            }
        
        }
        
        var p6 = Person6("김창수", 33);
            println(p6.name +", " + p6.age +", " +p6.isMinor)
        
            p6 = Person6("김영희", 18)
            println(p6.name +", " + p6.age +", " +p6.isMinor)
        
            p6.age = -1                           // Exception
            p6.age = 300                          // Exception
        ```
        
- lazy
    - 변수를 선언할 때 초기화 코드도 함께 정의하고 변수가 사용될 때 (최초) 초기화 코드가 단 한번 동작하여 변수를 초기화 한다.
        
        ```kotlin
        class LazyClass(var x: Int) {
        
            val lazyValue1 by lazy {
                println("lzt 람다 식 내부에서 속성 값 초기화 진행")
                var s = " HELLO "
                s.toLowerCase().trim()      // 이 값이 return 되어 필드에 대입 된다.
            }
        
            val lazyValue2 by lazy {
                x * 2
            }
        
            val costHeavyProperty by lazy {
                println("시간이 오래 걸리고 메모리 사용량이 많은 속성값 초기화 진행")
                Thread.sleep(2000)                      // 2초 시간 지연
                Array<Byte>(1024 * 1024 * 100) {0}  // 100MByte의 배열 선언
            }
        }
        
        var lazyClass = LazyClass(10)
        
        println(lazyClass.lazyValue1)                   // 최초 1번만 실행
        println(lazyClass.lazyValue1)
        println(lazyClass.lazyValue1)
        
        // 비용이 많이 드는 작업은 접근 시 람다식 내부가 동작하는 것이 합리적이다.
        println(lazyClass.costHeavyProperty)
        ```
        
- lateInit
    - 처음에 아무값도 대입하지 않고 쓰기 위한 키워드
    - val은 사용이 불가능하다.  (해당 필드의 setter를 써서 값을 넣어줘야 하기 때문에)
    - lateinnit 프로퍼티는 오직 클래스 타입(레퍼런스 타입)만 지원한다. (기본자료형 or String은 안됨)
    - 필요한 시점에 해당 필드를 초기화 해서 사용할 수 있다.
    - 객체를 만드는데는 문제 없이 보장한다고는 하지만 초기화하지 않고 해당 필드를 활용할 때 Exception이 발생할 수 있으므로 주의를 요함(초기화를 잊으면 안 된다.)
        
        ```kotlin
        class LateInitClass {
            lateinit var obj: PropertyObject                // 변수의 초기화를 뒤로 미루고, 선언만 시행한다.
        
            fun initMyObject(value : PropertyObject) {
                obj = value
            }
        
            fun userMyobject() {
                obj.func()
            }
        }
        
        var lateInitClass = LateInitClass()
        lateInitClass.initMyObject(PropertyObject())    // 필드 초기화
        lateInitClass.userMyobject()                    // 초기화 이후 필드를 활용한 기능 호출
        
        ```
        
- Data Class
    - 클래스의 내용({})을 쓰지 않아도 필드 두 개 선언 및 getter/setter 부터 toString까지 모두 정의 된다.
        
        <aside>
        💡 data class PersonClass(var name: String, var age: Int)
        
        </aside>
        
    - 단순히 값을 저장하기 위한 용도로 클래스를 활용하려면 data 키워드를 이용해서 클래스를 데이터 클래스로 정의할 수 있다.
    - 데이터 클래스로 정의하면 유용하게 활용할 수 있는 여러 메소드를 자동으로 구현해 준다. (단! 주생성자에 정의된 속성만 이용해서 메소드의 내용을 구성한다.)
    - <toString>
        - 객체의 내용을 문자열로 반환
        - 클래스 이름(속성1 = 속성값1, 속성2 = 속성값2, ..., 속성n = 속성값n)
            
            ```kotlin
            var personToString = p1.toString()
            println(personToString)
            println(p2)
            ```
            
    - <equals>
        - 두 객체가 지닌 속성값(내용)이 같은지 비교(논리적으로 비교)하고 그 결과를 boolean값으로 반환
            
            ```kotlin
            println("equals: ${p1.equals(p2)}")
            println(p1 == p2)           // 동등 하므로 true
            println(p1 === p3)          // 동일하지 않으므로 false
            println(p1 == p3)           // 동등하지 않으므로 false
            ```
            
    - <hashCode>
        - 객체가 논리적으로 같은 값을 가지고 있는지 여부를 좀 더 효율적으로 검사하기 위해 사용되는 메소드
        - (컬렉션의 map을 사용할 때는 equals와 hashCode를 오버라이딩 해야 한다.(키 값 동등비교 때문에..)
            
            ```kotlin
            println(p1.hashCode())
            println(p2.hashCode())
            println(p3.hashCode())
            ```
            
    - <copy>
        - 원본 객체에 있는 모든 속성값들이 복사 된 새로운 객체가 생성된다
            
            ```kotlin
            var copied1 = p1.copy()
            println(copied1)            // 사본 확인
            println(copied1 == p1)      // 사본이 동동한 값을 가졌는지 확인
            println(copied1 === p1)     // 깊은 복사인지 얕은 복사인지 확인 (깊은 복사)
            ```
            
    - <componentN>
        - 객체의 속성값을 반환하는 메소드, 데이터 클래스에 포함 할 속성의 개수만큼 component 메소드가 생성된다.
            
            ```kotlin
            var (p1Name, p1Age) = p1
            println(p1Name)
            println(p1Age)
            
            var p1Name = p1.component1()
            var p1Age = p1.component2()
            println(p1Name)
            println(p1Age)
            ```
            
- Companion Object
    - object는 class와 달리 인스턴스가 하나만 있는 싱글통 선언 방법이다.
    - 동반자 객체는 클래스나 인터페이스가 하나만 지닌 객체이고 자바의 public static 개념에 하나의 인스턴스로 존재(singleton)하는 것이다.
        - Companion Object는 어떤 클래스의 모든 인스턴스가 공유하는 객체를 만들 때 사용하며 클래스 당 한 개만 선언할 수 있다. (이름을 지어줄 수도 있다.)
            
            ```kotlin
            class CompClass1{
                companion object TestComp {         // public static singleton
                    val prop = "CompClass1에 작성 된 TestComp Companion Object의 prop 속성"
                    fun method() = "CompClass1에 작성 된 TestComp Companion Object의 method"
                }
            }
            
            val x1 = CompClass1.TestComp
            println(x1.prop)
            println(x1.method())
            ```
            
        - Compnion Object는 이름이 생략될 수 있으며 Companion이라는 식별자를 통해 접근 가능하다.
            
            ```kotlin
            class CompClass2{
                companion object  {         // public static singleton
                    val prop = "CompClass1에 작성 된 이름 없는 Companion Object의 prop 속성"
                    fun method() = "CompClass1에 작성 된 이름 없는 Companion Object의 method"
                }
            }
            
            var x2 = CompClass2.Companion       // 동반자 객체의 이름이 없는 경우 Companion으로 호출 가능
            println(x2.prop)
            println(x2.method())
            ```
            
        - 클래스 안에 정의 된 companion object는 companion object를 지닌 클래스의 이름 만으로도 참조 접근이 가능하다.
            
            ```kotlin
            println(CompClass1.prop)
            println(CompClass2.method())
            ```
            
        - 인터페이스 내에서도 Companion Object를 정의할 수 있다.
            
            ```kotlin
            interface CompInterface{
                companion object  {         // public static singleton
                    val prop = "CompInterface 작성 된 이름 없는 Companion Object의 prop 속성"
                    fun method() = "CompInterface 작성 된 이름 없는 Companion Object의 method"
                }
            }
            val x3 = CompInterface.Companion
            println(x3.prop)
            println(x3.method())
            ```
            

# Inheritance

<aside>
💡 Kotlin은 class, function 모두 기본적으로 final이 붙어있다. 따라서 상속을 위해선 open 키워드가 필요하다.

</aside>

- 상속
    
    ```kotlin
    open class Animal(var name: String, var age: Int, val gender: String) {
        fun eat(food: String) {
            println("${name}이(가) ${food}를(을) 먹습니다.")
        }
    
        open fun sleep(hour: Int) {
            println("${name}이(가) ${hour}시간 동안 잡니다.")
        }
    }
    
    class Rabbit(name: String, age: Int, gender: String, var location: String, var weight: Int, var kinds: String) : Animal(name, age, gender) {
        fun jump(hour: Int) {
            println("${location}에 살고 있는 몸무게가 ${weight}kg인 ${kinds}종 토끼 ${name}이(가) 깡총깡총 뜁니다.")
        }
    
        override fun sleep(hour: Int) {
            println("sleep을 오버라이딩 함")
        }
    }
    
    fun main(args: Array<String>) {
        var rabbit1 = Rabbit("토끼", 25, "암컷", "강남", 100, "라이언헤드")
        var rabbit2 = Rabbit("비실토끼", 21, "수컷", "강원도", 5, "산토끼")
    
        rabbit1.eat("햄버거")
        rabbit2.jump(8)
    
        rabbit2.sleep(2)
    }
    ```
    
- 다형성
    
    ```kotlin
    open class Parent(var parentProp: Int) {
        fun parentFunc() {
            println("parentFunc")
        }
    }
    
    class Child(prop: Int, var childProp: Int) : Parent(prop) {
        fun childFunc() {
            println("${super.parentProp}")
            super.parentFunc()
            println("childFunc")
        }
    }
    
    fun main(args: Array<String>) {
    	var p = Parent(1)
      println("${p.parentProp}")
      var c = Child(1, 2)
      println("${c.parentProp}, ${c.childProp}")
    
      var poly: Parent = Child(3, 4)
      poly.parentFunc()           // Parent타입으로 인지된 공간
    
    //    poly as Child               // 코틀린에서 형변환은 as 키워드를 사용한다.
    //    poly.childFunc()
    
      if(poly is Child) {         // is는 java의 instanceof와 같은 연산자이다.(+ 다운 캐스팅도 해줌)
          poly.childFunc()
          println("${poly.parentProp}, ${poly.childProp}")
      }
    }
    ```
    

# Collection

<aside>
💡 Kotlin은 immutable(기본, 변경 불가능) Collection과 mutable(변경 가능) Collection이 있다.

</aside>

- List
    - mutableListOf *:* 변경 가능한 리스트(mutableList)를 선언하는 함수
        - 처음 선언 시에 비어있는 상태로 생성되는 리스트는 값이 대입되지 않아 타입 정보를 유추할 수 없으므로 에러가 발생한다. 리스트에 포함 될 자료의 타입을 명시해야 한다.
            
            ```kotlin
            var emptyMutableList: MutableList<String> = mutableListOf()
            
            /* String들이 담긴 MutableList를 바로 선언 */
            var mutableList = mutableListOf("사과", "바나나", "메론")
            ```
            
        - 요소의 추가 및 제거는 자바와 동일하다.
            
            ```kotlin
            /* add: 요소 뒤 값 추가 */
            mutableList.add("딸기")
            println("mutableList add element : $mutableList")
            
            /* 원하는 위치에 요소 추가(인덱스 활용) */
            mutableList.add(2, "수박")
            println("mutableList add index : $mutableList")
            
            /* remove, removeAt: 동등 요소 삭제 */
            mutableList.remove("메론")
            println("mutableList remove element : $mutableList")
            
            mutableList.removeAt(1)
            println("mutableList remove index : $mutableList")
            
            /* get: 값 추출 */
            var value1 = mutableList.get(1)
            println("mutableList.get(1): $value1")
            ```
            
        - get()의 경우 인덱스 접근 연산자도 가능하다.
            
            ```kotlin
            /* 인덱스 접근 연산자([]): 값 추출 */
            var value2 = mutableList[2]
            println("mutableList access index [2]: $value2")
            ```
            
        - Iterator를 사용할 필요 없이, for-in문으로 list, set, map 모두 요소를 꺼내올 수 있다.
            
            ```kotlin
            /* for-in 문으로 요소 꺼내오기 */
            println("mutableList loop: ")
            for(item in mutableList) {
                print("$item ")
            }
            println()
            ```
            
        - contains, size는 동일하다.
            
            ```kotlin
            /* contains: 요소 포함 여부 */
            println("mutableList contain 딸기: ${mutableList.contains("딸기")}")
            println("mutableList contain 포도: ${mutableList.contains("포도")}")
            
            /* size: 요소 개수 */
            println("mutableList size: ${mutableList.size}")
            ```
            
    - listOf: 변경 불가능한 리스트(immutableList)를 선언하는 함수
        
        ```kotlin
        var immutableList = listOf(1, 2, 3)
        //    immutableList.add(4)              // 추가 불가능
        //    immutableList.remove(1)           // 제거 불가능
            
        // 읽기는 가능
        println("immutable List index [2]: ${immutableList[2]}")
        ```
        
    - immutableList -> mutableList로 전환할 수 있다.
        
        ```kotlin
        var immutableToMutableList = immutableList.toMutableList()
        
            immutableToMutableList.add(4)
            immutableToMutableList.removeAt(4)
        ```
        
    - + 연산자로 두 리스트를 합칠 수 있다.(동일한 타입만 가능)
        
        ```kotlin
        var plusList = listOf('a', 'b', 'c') + listOf('d', 'e', 'f')
            println("list concat: $plusList")
        ```
        
    - 연산자로 앞의 리스트에서 뒤의 리스트의 내용을 삭제한 리스트를 얻을 수 있다.(중복 제거 가능)
        
        ```kotlin
        var substarctList = listOf(1, 2, 3, 1, 3, 4, 5, 2, 6) - listOf(2, 4, 6, 1)
            println("list substract set: $substarctList")
        ```
        
- Set
    - mutableSetOf: 변경 가능한 집합(mutableSet)을 생성하는 함수
        
        ```kotlin
        var mutableSet = mutableSetOf("축구", "농구", "수영")
        ```
        
    - add, remove 등은 동일하다.
        
        ```kotlin
        /* 값 추가 */
        mutableSet.add("야구")
        println("mutableSet add element: $mutableSet")
        
        /* 중복된 값은 추가되지 않음 */
        mutableSet.add("축구")
        mutableSet.add("농구")
        println("mutableSet add duplicated element: $mutableSet")
        
        mutableSet.remove("축구")
        println("mutableSet remove element: $mutableSet")
        
        /* for in 문을 활용한 Set 반복 */
        for(element in mutableSet) {
            print("$element ")
        }
        println()
        ```
        
    - 단, immutableSet은 get메소드나 인덱스 접근 연산자 사용 불가
- Map
    - Pair 타입은 객체 두 개를 넣을 수 있는 타입으로 자바의 Map에서의 key와 value의 쌍인 Entry 개념을 표현하는 자료형이다.
        
        ```kotlin
        var pair: Pair<String, Int> = "key" to 1
        ```
        
    - mutableMapOf: 변경 가능한 맵이자 매개변수로 전달받은 Pair들을 이용해 mutableMap을 만들 수 있게 해주는 함수
        
        ```kotlin
        //    var mutableMap = mutableMapOf("key1" to 1, "key2" to 2)
        var mutableMap = mutableMapOf(pair, "key2" to 2) // 선언된 pair 변수 사용 가능
        ```
        
    - 비어있는 맵을 선언할 경우에는 자료형(제네릭)을 생략할 수 없다.
        
        ```kotlin
        var emptyMutableMap = mutableMapOf<String, Int>()
        ```
        
    - 맵에 요소를 넣고, 지우는 방식은 자바와 동일하다.
        
        ```kotlin
        emptyMutableMap.put("key3", 3)
        println("empty mutableMap put pair: $emptyMutableMap")
        
        emptyMutableMap.put("key3", 4)          // 기존과 같은 키값을 지닌 pair(쌍)을 덮어 쓴다. (수정)
        println("empty mutableMap put pair with duplicated key: $emptyMutableMap")
        
        emptyMutableMap.remove("key3")
        println("mutableMap remove pair with key: $emptyMutableMap")
        ```
        
    - get("키값") 메소드 또는 []를 호출해 값에 접근할 수 있다.
        
        ```kotlin
        var valueFromKey2 = mutableMap.get("key2")
        println("mutablemap get Key2: $valueFromKey2")
        
        var valueFromkey1 = mutableMap["key"]
        println("mutableMap get key1: $valueFromkey1")
        ```
        
    - for in 문 역시 동일하다.
        
        ```kotlin
        for(pair in mutableMap) {
                print("$pair ")
            }
            println()
        ```
        
    - immutableMap은 아래와 같이 선언한다. 역시 값의 수정은 불가능하다.
        
        ```kotlin
        var immutableMap = mapOf("key1" to 1, "key2" to 2)
        ```