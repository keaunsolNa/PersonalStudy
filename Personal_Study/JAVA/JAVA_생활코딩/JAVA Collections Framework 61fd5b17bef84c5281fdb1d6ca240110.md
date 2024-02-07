# JAVA Collections Framework

# Collections Framework

- ArrayList의 사용법
    - 배열은 할당 시 주어진 인덱스 값을 벗어나는 배열을 선언할 수 없다.
    - 이 때 사용되는 것이 framework에서 제공하는 기능 중 하나인 ArrayList
    - ArrayList는 할당 시 인덱스 값을 할당하지 않으며, import 과정이 필요하다.
    - ArrayList의 인스턴스를 생성한 뒤, 인스턴스에 참조연산자(.)를 통해 add 키워드를 사용. 데이터를 추가할 수 있다. 이와 같은 차이점은 아래의 코드를 통해 명확히 확인이 가능하다.
    
    ```java
    package collections_framework;
    
    import java.util.ArrayList;
    
    public class Application {
    	public static void main(String[] args) {
    
    		String[] arrayObj = new String[2];
    		arrayObj[0] = "one";
    		arrayObj[1] = "two";
    		//arrayObj[2] = "three";	// 오류 발생
    		for(int i=0; i<arrayObj.length; i++) {
    			System.out.println(arrayObj[i]);
    		}
    		
    		ArrayList a1 = new ArrayList();
    		a1.add("one");
    		a1.add("two");
    		a1.add("three");
    		for(int i = 0; i<a1.size(); i++) {
    			System.out.println(a1.get(i));
    		}
    	}
    }
    ```
    
    - for문을 사용시, 배열의 .length와 달리, ArrayList는 .size()를 사용한다.
    - ArrayList의 add method는 어떠한 형태의 데이터 타입도 수용이 가능하다. 즉, add method의 인자는 데이터 타입이 Object라는 의미를 가진다. (”one”, “two”, “three”)
    
    ```java
    String value = a1.get(i);
    			System.out.println(value);
    ```
    
    - 따라서, 위와 같은 코드에서 에러가 발생하는 이유는 a1.get(i)를 통해 불러온 “one”의 데이터 타입은 object인데, 이를 String으로 규정했기에 발생하는 것이다.
    - 이 문제를 해결하기 위해선 a1.get(i)를 String 데이터 타입으로 형변환이 필요하다.
    - 단, 이는 간결하지 않은 방식으로, Generic을 이용하는 방법이 더 수월하다. 그 방식은 아래의 코드와 같다.
    
    ```java
    ArrayList<String> a1 = new ArrayList<String>();
    		a1.add("one");
    		a1.add("two");
    		a1.add("three");
    		for(int i = 0; i<a1.size(); i++) {
    			String value =  a1.get(i);
    			System.out.println(value);
    		}
    ```
    
    - 위 코드에서, Generic을 이용, a1 인스턴스의 데이터 타입을 String으로 규정했기에, 별도의 형변환이 없어도 a1.get(i)의 데이터 타입이 Object가 아닌 String으로 형변환이 가능하다.
- 전체적인 구성
    - Collections Framework는 다른 말로 컨테이너(Container)라고도 한다.
    - Collections Framework의 전체적인 구성은 아래와 같다.
    
    ![2.png](JAVA%20Collections%20Framework%2061fd5b17bef84c5281fdb1d6ca240110/2.png)
    
    - Collections Framework의 사용방법은 결국 위의 목록 중에서 원하는 기능을 찾아 사용하는 것.
    - Set은 서로 중복되지 않는 데이터를 배열로 묶을 때 주로 사용한다.
    - List는 중복 가능한 데이터를 배열로 묶을 때 주로 사용한다.
- List와 Set의 차이점
    - HashSet의 경우, 중복되는 데이터는 생성하지 않는다는 특징이 있다. (Stringpool과 유사) 따라서 아래의 코드의 경우, 출력값은 1,2,3이 된다.
    
    ```java
    HashSet<Integer> A = new HashSet<Integer>();
    		A.add(1);
    		A.add(2);
    		A.add(3);
    		A.add(3);
    		A.add(3);
    
    Iterator hi = (Iterator) A.iterator();
    		while(hi.hasNext()) {
    			System.out.println(hi.next());
    		}
    ```
    
    - 반면 ArrayList의 경우, 중복되는 데이터도 생성하는 특징이 있다. 따라서, 위의 코드와 달리 아래의 코드는 그 출력값이 3,4,4,4,5가 된다.
        
        ```java
        ArrayList<Integer> B = new ArrayList<Integer>();
        		B.add(3);
        		B.add(4);
        		B.add(4);
        		B.add(4);
        		B.add(5);
        
        Iterator hi = (Iterator) B.iterator();
        		while(hi.hasNext()) {
        			System.out.println(hi.next());
        		}
        ```
        
    - 즉, ArrayList는 입력한 5개의 숫자에 맞춰 5개의 크기가 배정되지만, HashSet의 경우. 5개의 숫자를 입력해도 중복된 데이터는 무시하고, 별도의 데이터인 1,2,3만 배정된다.
    - 이는 ArrayList와 HashSet만의 특징이 아닌, 상위 카테고리인 List와 Set의 특성이다. 즉, ArrayList, Vector, LinkedList는 중복된 값을 허용하며, HashSet, LinkedHashSet, TreeSet은 중복된 값을 허용하지 않는 것.
    - 하위 카테고리는 상위 카테고리의 공통된 특징을 공유하면서, 개별적인 차이점이 존재하는 것이다.
- Set이란?
    - Set은 벤다이어그램의 집합과 동일한 개념을 가진다.
    - 집합 안의 각각의 값들은 고유하며, 중복되지 않는다는 특징을 가지고 있는데, 이 개념을 프로그래밍적으로 옮겨 놓은 것이 바로 Set의 개념.
    
    ```java
    HashSet<Integer> A = new HashSet<Integer>();
    		A.add(1);
    		A.add(2);
    		A.add(3);
    		
    		HashSet<Integer> B = new HashSet<Integer>();
    		B.add(3);
    		B.add(4);
    		B.add(5);
    		
    		HashSet<Integer> C = new HashSet<Integer>();
    		C.add(1);
    		C.add(2);
    		
    		System.out.println(A.containsAll(B));
    		System.out.println(A.containsAll(C));
    ```
    
    - 위의 코드의 출력값은 각각 false와 true가 된다. 이는 Set이 집합의 개념을 가지고 있으며, A.containsAll() method가 A 안에 B, C가 포함되어 있는지를 확인하는 method이기 때문이다.
    - 위에서 A와 B는 3이라는 교집합이 존재하지만, A에 없는 4,5라는 데이터가 있기에 false가 출력되고. A는 C 안에 있는 1,2의 데이터 타입을 모두 가지고 있기에 true가 출력된다.
    - 즉, 위의 containsAll method는 B가 A의 부분집합인지를 묻는 것.
    - 반면, A.addAll(B)의 경우, 합집합의 개념을 가진다. A 인스턴스에 B의 값들을 합치는 것. 따라서 아래의 코드는 1, 2, 3, 4, 5의 출력값을 가진다.
    
    ```java
    A.addAll(B);
    
    Iterator hi = (Iterator) A.iterator();
    		while(hi.hasNext()) {
    			System.out.println(hi.next());
    		}
    ```
    
    - 또한 A.retainAll(B); method의 경우, 교집합의 개념을 가진다. 즉, A와 B의 교집합인 3만을 A의 데이터 타입으로 가지게 한다는 것. 즉, 아래의 코드 출력값이 3이 된다.
    
    ```java
    A.retainAll(B);
    Iterator hi = (Iterator) A.iterator();
    		while(hi.hasNext()) {
    			System.out.println(hi.next());
    		}
    ```
    
    - 또한, A.removeAll(B); method의 경우 차집합의 개념을 가진다. 즉, A에서 B와의 교집합인 3을 제외한 데이터를 가지게 한다는 것. 즉, 아래의 코드 출력값이 1,2가 된다.
    
    ```java
    A.removeAll(B);
    Iterator hi = (Iterator) A.iterator();
    		while(hi.hasNext()) {
    			System.out.println(hi.next());
    		}
    ```
    
    <aside>
    💡 추가로, List는 안의 데이터 타입이 순서대로 정리된다는 특징을 가진다. 따라서 순서대로 출력된다. 단, Set은 순서 없이 정리되기에, 순서대로 출력된다는 보장이 없다.
    
    </aside>
    
- Collection Interface
    
    ![1.gif](JAVA%20Collections%20Framework%2061fd5b17bef84c5281fdb1d6ca240110/1.gif)
    
    - 앞서 전체적인 구성에서 살펴봤던 Collection의 세부적인 구성도.
    - Collection, List, SortedSet와 같은 것들은 Interface이며. AbstractCollection, HashSet, ArrayList와 같은 것들은 Class이다. 이들은 모두 상속관계로 연결되어 있다. 상속관계에 맞춰 각각의 class와 interface가 사용할 수 있는 API가 다르다.
    - 위 구성도를 통해, List는 get / set method를 통해 순서대로(index) 값을 가지는데 반해, Set에는 순서가 없어 관련 API가 없음을 확인할 수 있다.
    - 즉. 상위 class의 interface에 따른 규약을 가진다.
- Iterator
    - Iterator는 컨테이너에 있는 값들을 하나 하나 꺼내서, 하나 하나 어떠한 처리를 해 줄 수 있도록 하는 역할을 한다.
    
    ```java
    HashSet<Integer> A = new HashSet<Integer>();
    		A.add(1);
    		A.add(2);
    		A.add(3);
    Iterator hi = A.iterator();
    		while(hi.hasNext()) {
    			System.out.println(hi.next());
    		}
    ```
    
    - 위 코드의 경우, 다음의 순서를 가지며 실행된다. 
    먼저, HashSet 클래스의 A라는 레퍼런스 변수의 값을, .add 를 통해 1,2,3 을 추가한다.
    - 그 다음, A 레퍼런스 변수(HashSet)에 있는 Iterator API 를 참조연산자(.)를 통해 호출한 뒤, 해당 기능을 hi라는 레퍼런스 변수에 대입한다.
    - 이후, while반복문이 실행되는데, hi.hasNext()를 통해 hi라는 레퍼런스 변수에 hasNexr() method를 통해 호출할 값이 있는지 확인 한다.
    - 첫 시도 때, hi 레퍼런스 변수에는 1, 2, 3이라는 값이 있기에 while문의 조건식은 true가 되며. System.out.println을 통해 hi.next()의 값인 1을 출력한다.
    - 이후, hi라는 레퍼런스 변수에 있는 값은 1을 제외한 2,3이 된다.
    단, 이는 hi 레퍼런스 변수의 값이 사라진 것일 뿐. A의 값은 여전히 1, 2, 3이다.
    - 이후, while 반복문에 의해 hi 레퍼런스 변수에 남은 값인 2, 3도 순서대로 출력한다.
    - 이후, 더 이상 출력할 값이 없는 hi 레퍼런스 변수는 while문에 의해 false가 되기에. 더 이상 출력되지 않고 while문이 종료된다.
    - 따라서, 해당 코드의 출력값은 1, 2, 3이 된다.
    - 또한. iterator는 HashSet이 아닌, Collection의 API 이기에. 값 규정 시 HashSet이 아닌 ArrayList를 사용하더라도 동일하게 출력된다. 
    즉. ArrayList나 HashSet 뿐만 아니라, Collection을 상속받은 모든 class와 interface는 Iterator를 구현 가능하다. 
    (다형성의 개념 역시, 적용 가능하다.)
- Map의 기본 사용법 1
    - Map은 Key값으로 지정된 데이터에 해당하는 값을 value에 저장한다. 예를 들러 Key값의 “one” 이라는 데이터 타입의 value를 1로 지정했을 때. 이후 컨테이너에서 “one”을 호출 시 1이 호출된다는 특성을 가지고 있다.
    - 단, Key값은 중복이 불가능하지만, value값은 중복될 수 있다는 특징이 있다.
    - 만약 이미 “one” 키 값의 value로 1이 지정되어 있는 상황에서, “one” 키 값의 value로 200을 지정한다면. 키 값은 중복을 허용되지 않기에 “one”의 value값이 1이 아닌 200으로 된다.
    
    ```java
    package collections_framework;
    
    import java.util.HashMap;
    
    public class Application4 {
    	public static void main(String[] args) {
    		HashMap<String, Integer> a = new HashMap<String, Integer>();
    		a.put("one", 1);
    		a.put("two", 2);
    		a.put("three", 3);
    		a.put("four", 4);
    		System.out.println(a.get("one"));
    		System.out.println(a.get("two"));
    		System.out.println(a.get("three"));
    		
    		iteratorUsingForEach(a);
    		iteratorUsingIterator(a);
    	}
    }
    ```
    
    - 위의 코드를 통해 본다면. 우선 HashMap class의 Generic 값으로 두 개의 데이터 타입 (String, Integer)를 지정한다. 이후, a라는 레퍼런스 변수로 HashMap의 인스턴스를 지정한다.
    - 이후, a레퍼런스 변수에 put api를 참조연산자를 통해 호출한뒤, 각각의 generic 데이터 타입인 String에 “one”, “two”, “three”, “four”를 지정하며. 이를 Key 값이라고 한다.
    - 이후, Integer 데이터 타입에는 1, 2, 3, 4를 지정하는데, 이를 Value 값이라고 한다.
    - 이 때, one이라는 키의 value는 1. two라는 키의 value는 2가 되는 식으로 이루어진다.
    - 이후, System.out.println(a.get(”one”));을 통해. 출력한다면. a.get을 통해 “one”이라는 키 값의 value를 호출. 1이 출력되게 된다.
    - 따라서, 위의 코드는 1,2,3이 출력된다.
- Map의 기본 사용법 2
    - 위 코드의 연장선으로, 두 개의 method를 만든다. 그 method는 아래와 같다.
    
    ```java
    static void iteratorUsingForEach(HashMap map) {
    		Set<Map.Entry<String, Integer>> entries = map.entrySet();
    		for(Map.Entry<String, Integer> entry : entries) {
    			System.out.println(entry.getKey() + " : " + entry.getValue());
    		}
    	}
    	
    	static void iteratorUsingIterator(HashMap map) {
    		Set<Map.Entry<String, Integer>> entries = map.entrySet();
    		Iterator<Map.Entry<String, Integer>> i = entries.iterator();
    		while(i.hasNext()) {
    			Map.Entry<String, Integer> entry = i.next();
    			System.out.println(entry.getKey()+ " : " + entry.getValue());
    		}
    	}
    ```
    
    - 우선, 앞서 method를 호출하여, iteratorUsingForEach method의 parameter인 map의 값이 매개변수 a가 된다.
    - 이후 map에 내장된 entrySet method를 통해 Set 데이터 타입에 리턴된 객체가 entries라는 변수에 담기게 된다.  
    Set<Map.Entry<String, Integer>> entries = map.entrySet();
    - 이후 Set 컨테이너에 담겨있는 각각의 값들은 Map.Entry 인터페이스에 담기게 된다.
    - Map.Entry에는 두 개의 중요한 method가 있는데, 각각 getKey()와 getValue이다. 이 때, getKey()의 데이터 타입은 String, getValue의 데이터 타입은 Integer가 된다. (Generic을 통해)
    - 이후, entries 변수는 for문의 entries가 된다. 
    for(Map.Entry<String, Integer> entry : entries) 
    결국, entries에 있는 값들을 하나 하나 꺼내 entry에 담게 된다.
    - 그 후, 
    System.out.println(entry.getKey() + " : " + entry.getValue());
    를 통해 각각의 Key값과 Value값을 꺼내어 출력한다.
    - 결국 출력값은 
    four : 4
    three : 3
    two : 2
    one : 1
    이 출력된다. (Key값의 알파벳 순서대로 출력된다.)
    - 이와 같은 방식을 이용한 이유는, Map에는 Iterator 기능이 없기에, Iterator 기능이 있는 Set을 만든 뒤. Map.entry를 통해 Map에 있는 데이터를 Set에 지정한다. 그 후 getKey()와 getValue()를 통해 Set에 있는 데이터들을 꺼내는 것.
    - 결국. Map은 수학의 함수 개념을 프로그래밍 적으로 구현시켰다고 볼 수 있다. Key가 함수의 정의역, Value가 함수의 공역.
- Collections의 사용법과 정렬
    
    ```java
    package collections_framework;
    
    import java.util.*;
     
    class Computer implements Comparable{
        int serial;
        String owner;
        Computer(int serial, String owner){
            this.serial = serial;
            this.owner = owner;
        }
        public int compareTo(Object o) {
            return this.serial - ((Computer)o).serial;
        }
        public String toString(){
            return serial+" "+owner;
        }
    }
     
    public class Application5 {
         
        public static void main(String[] args) {
            List<Computer> computers = new ArrayList<Computer>();
            computers.add(new Computer(500, "egoing"));
            computers.add(new Computer(200, "leezche"));
            computers.add(new Computer(3233, "graphittie"));
            Iterator i = computers.iterator();
            System.out.println("before");
            while(i.hasNext()){
                System.out.println(i.next());
            }
            Collections.sort(computers);
            System.out.println("\nafter");
            i = computers.iterator();
            while(i.hasNext()){
                System.out.println(i.next());
            }
        }
     
    }
    ```
    
    - 우선 위의 코드를 보자. main method 에서 Computer를 데이터 타입으로 하는 computers 레퍼런스 변수는 Computer를 데이터 타입으로 하는 ArrayList class의 인스턴스를 참조하고 있다.
    - 이후, .add를 통해 각각의 computers 레퍼런스 변수는 500, 200, 3233이라는 Key값과 “egoing”, “leezche”, “graphittie”라는 Value 값을 가지게 설정한다.
    - 이후, computers.iterator();를 통해 computers 레퍼런스 변수는 iterator() method의 기능을 i라는 변수에 담는다.
    - 이후, while문을 이용, hasNext를 통해 출력 시 출력 값은 add한 순서대로 출력이 된다.
    - 반면. sort라는 API를 통해 Collections class의 computers 매개변수를 정렬 할 경우.
    - while문을 이용하여 출력한 결과는 앞서 Comparable를 implements 한 Computer 클래스의 method에 따라 규정된다.
    - 그 method는 아래와 같은데
    return this.serial - ((Computer)o).serial;
    이를 통해 serial 값 - serial 값이 진행. 결과값이 양수면 this가 가리키는 원 객체가 크고, 결과값이 음수면 비교대상인 인자로 전달받은 객체가 크다는 식을 도출할 수 있다.
    - 따라서, sort는 작은 순서대로 정렬하는 특성을 가진 method이므로, serial 값인 500, 200, 3233이 작을 수록 앞으로 정렬되게 된다.
    - 결국, 출력값은 200 leezche, 500 egoing, 3233 graphittie 순으로 출력이 된다.
    - Collections Framework는 선배 개발자들이 만든 알고리즘을 API처럼 사용하는 것이다.
- 수업을 마치며
    - 수업의 마무리.