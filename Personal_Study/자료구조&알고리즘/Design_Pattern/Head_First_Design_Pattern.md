# Head First Design Pattern

# 디자인 패턴 소개

- 디자인 원칙
    1. 애플리케이션에서 달라지는 부분을 찾아내고, 달라지지 않는 부분으로부터 분리시킨다.
    → (캡슐화를 활용하라)
    2. 구현이 아닌 인터페이스에 맞춰서 프로그래밍한다.
    → 상위 형식에 맞춰서 프로그래밍한다. (다형성을 활용하라)
    3. 상속보다는 구성(composition)을 활용한다.
    → 두 클래스를 A에는 B가 있다의 관계로 합치는 것.
    4. 서로 상호작용을 하는 객체 사이에서는 가능하면 느슨하게 결합하는 디자인을 사용해야 한다. (상호의존성 최소화)
    5. 클래스는 확장에 대해서는 열려 있어야 하지만 코드 변경에 대해서는 닫혀 있어야 한다. (OCP, Open-Closed Principle)
    6. 추상화된 것에 의존하도록 만들어라. 구상 클래스에 의존하도록 만들지 않아야 한다. (Dependency Inversion Principle, 의존성 뒤집기 원칙)
    → 고수준 구성요소가 저수준 구성요소에 의존하면 안된다. 항상 추상화에 의존해야 한다.
    7. 최소 지식 원칙(Principle of Least Knowledge) : 정말 친한 친구하고만 이야기하라.  
    → 시스템을 디자인할 때, 어떤 객체든 그 객체와 상호작용을 하는 클래스의 개수에 주의해야 하며, 그런 객체들과 어떤 식으로 상호작용을 하는지에도 주의를 기울여야 한다.
    → 어떤 메소드에서든지 다음 네 종류의 객체의 메소드만을 호출하라. : 
        → 객체 자체
        → 메소드에 매개변수로 전달된 객체
        → 그 메소드에서 생성하거나 인스턴스를 만든 객체
        → 그 객체에 속하는 구성요소(인스턴스 변수에 의해 참조되는 객체)
    → 데메테르의 법칙(Law of Demeter)라는 말로도 사용된다. 
        
        ![Untitled](Head_First_Design_Pattern/Untitled.png)
        
        ![Untitled](Head_First_Design_Pattern/Untitled%201.png)
        
    8. 헐리우드 원칙(Hollywood Principle) : 먼저 연락하지 마세요. 저희가 연락 드리겠습니다. 
    → 의존성 부패(dependency rot)를 방지 하기 위해 사용한다. 
    —→ 어떤 고수준 구성요소가 저수준 구성요소에 의존하고, 저수준 구성요소는 다시 고수준 구성요소에 의존하는 식으로 의존성이 복잡하게 꼬여있는 것을 의존성 부패라고 한다.
    → 헐리우드 원칙을 사용하면, 저수준 구성요소에서 시스템에 접속은 할 수 있지만, 언제 어떤 식으로 그 구성요소들을 사용할지는 고수준 구성요소에서 결정하게 된다. 
        
        ![Untitled](Head_First_Design_Pattern/Untitled%202.png)
        
    9. 클래스를 바꾸는 이유는 한 가지 뿐이어야 한다.
    → 한 역할은 한 클래스에서만 맡게 해야 한다.
    
- 디자인 패턴이란?
    - 패턴이란 특정 컨텍스트 내에서 주어진 문제에 대한 해결책이다.
    → 컨텍스트(Context) : 패턴이 적용되는 상황. 반복적으로 일어나야 한다.
    → 문제(problem) : 그 컨텍스트 내에서 이루고자 하는 목적, 생길 수 있는 제약조건
    → 해결책(solution) : 누구든지 적용해서 일련의 제약조건 내에서 목적을 달성할 수 있는 일반적인 디자인
    - 어떤 Context 내에서 일련의 제약조건에 의해 영향을 받을 수 있는 문제에 봉착 했다면, 그 제약조건 내에서 목적을 달성하기 위한 해결책을 찾아낼 수 있는 디자인을 적용하면 된다.
    - 모든 패턴은 시스템의 일부분을 다른 부분과 독립적으로 변화시킬 수 있는 방법을 제공하기 위한 것이다.
    - 디자인 패턴은 클래스와 객체를 구성하여 어떤 문제를 해결하는 방법을 제공한다. 즉, 디자인 패턴은 라이브러리나 프레임워크가 아니다.
    - 패턴은 디자인을 할 때, 지금 디자인상의 문제에 적합하다는 확신이 들 경우에 패턴을 도입해야 한다.
    - 실질적인 확장성만을 추구하고, 별 근거 없이 일반화시키지 말아야 한다. 꼭 필요한 부분에서만 확장성을 고려햐야 한다.
- 패턴의 범주
    - 용도에 따른 범주는 다음과 같다.
        - 생성 관련 패턴(Creational pattern) : 객체 인스턴스 생성을 위한 패턴으로, 클라이언트와 그 클라이언트에서 생성해야 할 객체 인스턴스 사이의 연결을 끊어주는 패턴이다
        - 행동 관련 패턴(Behavioral Pattern)은 클래스와 객체들이 상호작용하는 방법 및 역할을 분담하는 방법과 관련된 패턴이다
        - 구조 관련 패턴(Structural pattern)은 클래스 및 객체들을 구성을 통해서 더 큰 구조로 만들 수 있게 해 주는 것과 관련된 패턴이다.
            
            ![Untitled](Head_First_Design_Pattern/Untitled%203.png)
            
    - 클래스를 다루는지, 객체를 다루는지에 따라 패턴을 분류하기도 한다.
        - 클래스 패턴(Class Pattern) 은 클래스 사이의 관계가 상속을 통해서 어떤 식으로 정의되는지를 다룬다. 클래스 패턴에서는 컴파일 시에 관계가 결정된다.
        - 객체 패턴(Object Pattern)에서는 객체 사이의 관계를 다루며, 객체 사이의 관계는 보통 구성을 통해서 정의된다. 객체 패턴에서는 일반적으로 실행 중에 관계가 생성되기 때문에 더 동적이고 유연하다.
        
        ![Untitled](Head_First_Design_Pattern/Untitled%204.png)
        

![Untitled](Head_First_Design_Pattern/Untitled%205.png)

![Untitled](Head_First_Design_Pattern/Untitled%206.png)

# Strategy Pattern

- 스트래티지 패턴에서는 알고리즘군을 정의하고 각각을 캡슐화하여 교환해서 사용할 수 있도록 만든다. 스트래티지를 활용하면 알고리즘을 사용하는 클라이언트와는 독립적으로 알고리즘을 변경할 수 있다.

![Untitled](Head_First_Design_Pattern/Untitled%207.png)

- Strategy Pattern의 핵심은 캡슐화와 다형성에 있다. 이는 단순히 알고리즘군을 별도의 클래스의 집합으로 캠슐화할 수 있도록 만들어주는 것 뿐 아니라, 구성요소로 사용하는 객체에서 올바른 행동 인터페이스를 구현하기만 하면 실행시에 행동을 바꿀 수도 있게 해 준다.
- 알고리즘의 각 단계를 구현하는 방법을 서브클래스에서 구현한다.
- 스트래티지 패턴은 행동들이 쉽게 확장하거나 변경할 수 있는 클래스들의 집합으로 캡슐화되어 있다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%208.png)
    

![Untitled](Head_First_Design_Pattern/Untitled%209.png)

# Observer Pattern

- 옵저버 패턴에서는 한 객체의 상태가 바뀌면 그 객체에 의존하는 다른 객체들한테 연락이 가고 자동으로 내용이 갱신되는 방식으로 일대다(one-to-many)의존성을 정의한다.
→ 일대다 관계는 주제와 옵저버에 의해 정의된다. 옵저버는 주제에 의존한다.
- 상태가 변경되면 다른 객체들한테 연락을 돌릴 수 있게 해 준다.

![Untitled](Head_First_Design_Pattern/Untitled%2010.png)

![Untitled](Head_First_Design_Pattern/Untitled%2011.png)

- 옵저버 패턴은 주제(새로운 값을 뿌리는 객체)와 옵저버(새로운 값을 구독하는 객체)로 이루어져있다.
- 옵저버 패턴을 구현하는 대표적인 방법은 주제(Subject) 인터페이스와 옵저버(Observer) 인터페이스가 들어있는 클래스 디자인을 바탕으로 한다.
- 옵저버 패턴에서는 주제와 옵저버가 느슨하게 결합되어 있는 객체 디자인을 제공한다.
→ 느슨한 결합 : 두 객체가 상호작용을 하긴 하지만 서로에 대해 잘 모른다는 것을 의미한다. 즉, 주제가 옵저버에 대해 아는 것은 옵저버가 특정 인터페이스(Observer 인터페이스)를 구현한다는 것 뿐이다.
- 옵저버는 언제든지 새로 추가할 수 있으며, 추가할 때 주제를 변경할 필요가 없다.
- 주제와 옵저버는 서로 독립적으로 재사용할 수 있다.
- 주제나 옵저버가 바뀌더라도 서로한테 영향을 미치지 않는다.
- 옵저버 패턴의 구현에는 직접 Subject와 Observer Interface를 만들어 구현하는 방법과, Java.util.Observable API를 Import하여 구현하는 방법이 있다. 단, Observable은 인터페이스가 아닌 클래스로, 어떤 인터페이스를 구현하는 것도 아니라서 활용도와 재사용성에 있어서 제약조건으로 작용하는 문제점이 몇 가지 있다.
→ Observable이 클래스이기에 서브 클래스를 만들어야 한다. 
→ Observable 인터페이스가 없어 Observer API와 잘 맞는 클래스를 직접 구현하는 것이 불가능하다.
→ setChanged() method가 protected로 선언되어 있어 상속보다는 구성을 사용하는 디자인 원칙에 위배된다.

![Untitled](Head_First_Design_Pattern/Untitled%2012.png)

# Decorator Pattern

- 데코레이터 패턴에서는 객체에 추가적인 요건을 동적으로 첨가한다. 데코레이터는 서브클래스를 만드는 것을 통해서 기능을 유연하게 확장할 수 있는 방법을 제공한다.
- 데코레이터 패턴의 동작 순서
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2013.png)
    
- 데코레이터 패턴의 가장 대표적인 예시인 JAVA I/O 라이브러리
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2014.png)
    

- 데코레이터의 슈퍼클래스는 자신이 장식하고 있는 객체의 슈퍼클래스와 같다.
- 한 객체를 여러 개의 데코레이터로 감쌀 수 있다.
- 데코레이터는 자신이 장식하고 있는 객체에게 어떤 행동을 위임하는 것 외에 원하는 추가적인 작업을 수행할 수 있다.
- 구성과 위임을 통해서 실행 중 새로운 행동을 추가할 수 있다.
- 데코레이터 패턴에서는 상속을 이용해서 형식을 맞추며, 상속을 통해서 행동을 물려받는 것은 목적이 아니다.
- 상속만 사용할 경우 행동이 컴파일 시 정적으로 결정된다. 하지만 구성을 활용하면 실행 중 동적으로 데코레이터를 조합해서 사용할 수 있다. 
→ 디자인 패턴 원칙, 상속보다는 구성을 활용하라
- 데코레이터 패턴에서는 구상 구성요소를 감싸주는(Wrapper) 데코레이터를 사용한다.
- 구상 구성요소의 형식을 알아내서 그 결과를 바탕으로 작업을 처리하는 코드에 대해서는 데코레이터 패턴 적용시 코드가 제대로 작동하지 않는다. 즉, 추상 구성요소 형식을 바탕으로 돌아가는 코드에 대해서 데코레이터 패턴을 적용해야만 제대로 된 결과를 얻을 수 있다.
- 데코레이터 패턴의 단점은 아래와 같다.
→ 자잘한 클래스가 추가되는 경우가 많다.
→ 특정 형식에 의존하는 코드에 데코레이터 코드를 적용할 수 없다.
→ 데코레이터 도입 시 구성 요소를 초기화하는 데 필요한 코드가 훨씬 복잡해진다는 단점이 있다.
- 데코레이터 패턴은 책임과 관련된 일을 하며, 데코레이터가 적용된다는 것은 새로운 책임 또는 행동이 디자인에 추가된다는 것을 의미한다.

![Untitled](Head_First_Design_Pattern/Untitled%2015.png)

# Factory Pattern

- 팩토리 메소드 패턴에서는 객체를 생성하기 위한 인터페이스를 정의한다. 단, 어떤 클래스의 인스턴스를 만들지는 서브클래스에서 결정하게 만든다.
→ 여기서 결정한다는 말은 생산자 클래스 자체가 실제 생산될 제품에 대한 사전 지식이 전혀 없이 만들어지기 때문이다. 즉, 사용하는 서브클래스에 따라 생산되는 객체 인스턴스가 결정된다.
- 추상 팩토리 패턴에서는 서로 연관된, 또는 의존적인 객체들로 이루어진 제품군을 생성하기 위한 인터페이스를 제공한다. 구상 클래스는 서브 클래스에 의해 만들어진다.
- 객체의 인스턴스를 만드는 작업이 항상 공개되어 있어야 하는 것은 아니다.
- “new”는 “구상 객체”를 의미한다. 즉, new를 사용하는 것은 구상 클래스의 인스턴스를 만드는 것이며, 인스턴스가 아닌 특정 구현을 사용하는 것이다.
→ 즉, new를 사용하는 것은 뭔가를 변경하거나 확장해야 할 때 재사용성과 유지보수성이 취약해짐을 의미한다.
→ 하지만 new 자체에 문제가 있는 것은 아니다. 문제를 일으키는 것은 “변화”임을 생각하자.
→ 그렇기에 인터페이스에 맞춰서 코딩을 해야 한다. 인터페이스에 맞춰 코딩을 하면 다형성에 힘 입어 어떤 클래스든 특정 인터페이스만 구현하면 사용할 수 있기 때문이다.

![Untitled](Head_First_Design_Pattern/Untitled%2016.png)

- 객체 구성을 활용하면 행동을 실행 시에 동적으로 바꿀 수 있다. 이는 구현된 객체를 변경할 수 있기 때문이다.
- 간단한 팩토리는 엄밀히 말하자면 디자인 패턴은 아니다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2017.png)
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2018.png)
    
- 팩토리 메소드는 객체 생성을 처리하며, 팩토리 메소드를 이용하면 객체를 생성하는 작업을 서브클래스에 캡슐화시킬 수 있다. 이렇게 하면 슈퍼클래스에 있는 클라이언트 코드와 서브 클래스에 있는 객체 생성 코드를 분리시킬 수 있다.
- 모든 팩토리 패턴에서는 객체 생성을 캡슐화한다. 팩토리 메소드 패턴(Factory Method Pattern)에서는 서브클래스에서 어떤 클래스를 만들지를 결정하게 함으로써 객체 생성을 캡슐화한다.
- 팩토리 메소드와 생산자 클래스는 반드시 추상으로 선언해야 하는 것은 아니다. 기본 팩토리 메소드를 정의해도 되며, 그럴 경우 Creator의 서브클래스를 만들지 않아도 제품을 만드는 것이 가능하다.
- 팩토리 메소드 패턴과 간단한 팩토리의 차이 : 
→ 간단한 팩토리 : 일회용 처방에 불과하다. 객체 생성을 캡슐화하는 방법을 사용하긴 하지만 팩토리 메소드 패턴에 비해 유연성이 약하다. 이는 생성하는 제품을 원하는대로 변경할 수 없기 때문이다.
→ 팩토리 메소드 패턴 : 어떤 구현을 사용할지를 서브클래스에서 결정하는 프레임워크를 만들 수 있다.
- 팩토리 패턴의 장점은 아래와 같다. 
→ 객체 생성 코드를 하나의 객체 또는 메소드에 집어 넣으면 중복되는 내용을 제거할 수 있다.
→ 위의 방법을 사용할 경우 유지보수성이 향상된다. 
→ 클라이언트 입장에서는 객체 인스턴스를 만들 때 필요한 구상 클래스가 아닌 인터페이스만 필요로 하게 된다. 이럴 경우, 구현이 아닌 인터페이스를 바탕으로 프로그래밍이 가능하며, 이는 유연성과 확장성이 증대된다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2019.png)
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2020.png)
    
- 의존성 뒤집기 원칙을 지키기 위한 가이드라인은 아래와 같다.
→ 어떤 변수에도 구상 클래스에 대한 레퍼런스를 저장하지 않는다.
→ 구상 클래스에서 유도된 클래스를 만들지 않는다.
→ 베이스 클래스에 이미 구현되어 있던 메소드를 오버라이드하지 않는다.
- 단, 위 가이드라인은 원칙은 아니다.
- 추상 팩토리 패턴 :  추상 팩토리 패턴에서는 인터페이스를 이용하여 서로 연관된, 또는 의존하는 객체를 구상 클래스를 지정하지 않고도 생성할 수 있다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2021.png)
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2022.png)
    
- 추상 팩토리 패턴과 팩토리 메소드 패턴의 차이 :
- 팩토리 메소드 패턴 : 

상속을 통해 객체를 만든다.

객체를 생성할 때 클래스를 확장하고 팩토리 메소드를 오버라이드 해야 한다.

구상 형식을 서브클래스에서 처리함으로써 클라이언트와 구상 형식을 분리시켜주는 역할을 한다.

팩토리 메소드를 사용해서 구상 팩토리를 구현하는 경우도 있다. 

클라이언트 코드와 인스턴스를 만들어야 할 구상 클래스를 분리시켜야할 때 유용하다. 

어떤 구상 클래스를 필요로 하게 될지 미리 알 수 없는 경우에도 매우 유용하다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2023.png)
    
- 추상 팩토리 패턴 : 

객체 구성(Composition)을 통해 객체를 만든다.

인스턴스를 생성한 뒤 추상 형식을 사용해서 코드에 전달하는 방식으로 클라이언트와 구상 제품을 분리한다.

새로운 제품을 추가하려면 인터페이스를 변경해야 한다.

서브클래스에서 만드는 구상 형식을 활용하는 추상 생산자에서 코드를 구현한다.

클라이언트에서 서로 연관된 일련의 제품들을 만들 때, 즉 제품군을 만들때 유용하다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2024.png)
    

# Singleton Pattern

- 싱글턴 패턴은 해당 클래스의 인스턴스가 하나만 만들어지고, 어디서든지 그 인스턴스에 접근할 수 있도록 하기 위한 패턴이다.
- 한 객체만 생성되도록 한다.
- 싱글턴 패턴은 특정 클래스에 대해서 객체 인스턴스가 하나만 만들어질 수 있도록 해 주는 패턴이다.
- 싱글턴 패턴을 사용 시 전역 변수를 사용할 때와 마찬가지로 객체 인스턴스를 어디서든지 엑세스할 수 있도록 해 준다. 또한, 전역 변수를 사용할 때의 단점을 감수하지 않아도 된다. 

→ 전역 변수에 객체를 대입 시 애플리케이션이 시작 될 때 객체가 생성(JVM 기준)된다. 이 때 해당 객체가 자원을 많이 차지한다면 리소스 낭비의 부담이 크다. 
→ 단, 싱글턴 패턴을 사용시 해당 객체를 사용할 때만 생성하므로, 리소스 낭비의 부담이 적다.
- 싱글톤 패턴은 레지스트리 설정이 담겨 있는 객체, 연결 풀, 스레드 풀과 같은 자원 풀을 관리하는데 주로 사용된다.
- 싱글톤 객체가 필요할 때는 new 연산자를 통해 인스턴스를 직접 만드는 것이 아닌, 정적 메소드를 통해 인스턴스를 요청 해야 한다.

![Untitled](Head_First_Design_Pattern/Untitled%2025.png)

![Untitled](Head_First_Design_Pattern/Untitled%2026.png)

![Untitled](Head_First_Design_Pattern/Untitled%2027.png)

- 단, 고전적인 싱글톤 패턴의 경우 멀티 스레드 환경에서 문제가 발생할 수 있다. getInstance() 와 같은 정적 메소드를 통해 인스턴스 요청 시, 하나의 요청이 종료되기 전에 다른 요청이 추가된다면 싱글톤 패턴은 하나의 인스턴스가 아닌 다른 인스턴스를 반환, 두 인스턴스가 동일한 인스턴스가 아니게 되기 때문이다. 

→ 이를 해결하기 위해 필요한 것은 synchronized를 통한 동기화 처리. 동기화 처리 시 하나의 스레드가 메소드 사용이 끝나기 전까지 다른 스레드는 기다려야 하므로, 정적 메소드를 동시에 실행하여 서로 다른 객체를 생성하는 일은 없게 할 수 있다. 
→ 단, 동기화가 필요한 시점은 메소드가 시작되는 순간 뿐이기에 메소드를 동기화한 상태로 유지할 필요가 없어 속도의 문제가 생길 수 있다.
- 이 문제를 해결하기 위한 방법은 정적 메소드가 수행하는 요청에 속도가 중요하지 않다면 그대로 유지해도 된다. 단, 이럴 경우 성능이 100배 정조 저하된다는 점에 유의하자.
- 인스턴스를 필요할 때 생성하지 말고, 처음부터 만들어버리는 방법도 있다. 이는 애플리케이션이 해당 인스턴스를 생성하고 항상 사용할 때 유용한 방법이다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2028.png)
    
- 가장 좋은 방법은 DCL(Douoble-Checking Locking)을 사용하여 정적 메소드에서 동기화되는 부분을 줄이는 방법이다.
- 이 방법은 인스턴스가 생성되어 있는지 확인 후, 생성되어 있지 않았을 때만 동기화를 할 수 있다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2029.png)
    
- 단, DCL 방식은 자바 1.4 이전 방법에서는 사용할 수 없다.
- 싱글턴은 자신의 인스턴스를 관리하는(그리고 전역 접근을 제공하는) 것 외에도 원래 그 인스턴스를 사용하고자 하는 목적에 부합하는 작업을 책임져야 한다. 그러므로 단일 책임의 원칙에 위배된다고 볼 수 있다.

# Command Pattern

- 커맨드 패턴을 이용하면 요구 사항을 객체로 캡슐화(일련의 행동을 특정 리시버하고 연결함으로써) 할 수 있으며, 매개변수를 써서 여러 가지 다른 요구 사항을 집어넣을 수도 있다. 또한 요청 내역을 큐에 저장하거나 로그로 기록할 수도 있으며, 작업취소 기능도 지원 가능하다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2030.png)
    
- 커맨드 패턴을 사용하면 작업을 요청한 쪽과 작업을 처리한 쪽을 분리할 수 있다.
- 커맨드 객체는 특정 객체에 대한 특정 작업 요청을 캡슐화시켜줄 수 있다.
- 커맨드 객체에서 execute()를 구현할 수도 있다. 하지만 이럴 경우 인보커와 리시버를 높은 수준으로 분리시키는 것이 불가능하며, 리시버를 이용해서 커맨드를 매개변수화하는 것도 할 수 없게 된다.
- Queue를 활용, 커맨드를 이용하여 컴퓨테이션(computation)의 한 부분(리시버와 일련의 행동)을 패키지로 묶어서 일급 객체 형태로 전달하는 것도 가능하다. 이럴 경우는 스케쥴러, 스레드 풀, 작업큐와 같은 다양한 용도에서 활용된다.
- 요청 내역을 객체로 캡슐화하여 클라이언트를 서로 다른 요청 내역에 따라 매개변수화 할 수도 있다. 요청을 큐에 저장하거나 로그로 기록할 수도 있고 작업취소 기능을 지원할 수도 있다.

![Untitled](Head_First_Design_Pattern/Untitled%2031.png)

![Untitled](Head_First_Design_Pattern/Untitled%2032.png)

![Untitled](Head_First_Design_Pattern/Untitled%2033.png)

![Untitled](Head_First_Design_Pattern/Untitled%2034.png)

# Adapter Pattern

- 한 클래스의 인터페이스를 클라이언트에서 사용하고자 하는 다른 인터페이스로 변환하는 것. 어댑터를 이용하면 인터페이스 호환성 문제 때문에 같이 쓸 수 없는 클래스들을 연결해서 쓸 수 있다.
- 객체지향 어댑터(Adapter)는 어떤 인터페이스를 클라이언트에서 요구하는 형태의 인터페이스에 적응시켜주는 역할을 한다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2035.png)
    
- 클라이언트에서 어댑터를 사용하는 방법은 아래와 같다.

→ 클라이언트에서 타겟 인터페이스를 사용하여 메소드를 호출함으로서 어댑터에 요청한다.
→ 어댑터에서는 어댑티 인터페이스를 사용하여 그 요청을 어댑티에 대한 (하나 이상의) 메소드 호출로 변환한다.
→ 클라이언트에서는 호출 결과를 받긴 하지만 중간에 어댑터가 껴 있는지는 전혀 알지 못 한다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2036.png)
    
- 어댑터를 구현하는 일은 타겟 인터페이스로 지원해야 하는 인터페이스의 크기에 비례해서 복잡해진다.
- Target과 Adaptee의 인터페이스를 모두 구현하는 어댑터가 양방향 어댑터(Two way adapter)다. adapter 객체는 Target 클래스를 처리하는 새 시스템에서 Target으로 사용되거나 Adaptee 클래스를 처리하는 다른 시스템에서 Adaptee로 사용될 수 있다.
- 어댑터 패턴을 이용함으로서 클라이언트와 구현된 인터페이스를 분리시킬 수 있으며, 인터페이스가 변경되더라도 그 변경 내역은 어댑터에 캡슐화되기에 클라이언트는 바뀔 필요가 없다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2037.png)
    
- 어댑터 패턴을 이용함으로서 클라이언트를 특정 구현이 아닌 인터페이스에 연결시킨다. (디자인 원칙 2번)
- 어댑터에는 객체 어댑터와 클래스 어댑터가 있다. 지금까지의 내용은 객체 어댑터에 대한 내용이며, 클래스 어댑터는 다중 상속이 필요하지만 Java에서는 다중 상속이 불가능하기에 Java에서는 클래스 어댑터를 사용할 수 없다.
- 클래스 어댑터의 클래스 다이어그램은 다음과 같다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2038.png)
    
- 클래스 어댑터에서는 어댑터를 만들 때 타겟과 어댑티 모두의 서브클래스를 만들고, 객체 어댑터에서는 구성을 통해서 어댑티에 요청을 전달한다는 점을 제외하면 별 다른 차이점이 없다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2039.png)
    
- 객체 어댑터는 구성(Composition)을 사용하며 어댑티 클래스 뿐 아니라 그 서브 클래스에 대해서도 어댑터 역할을 할 수 있다.
- 클래스 어댑터는 특정 어댑티 클래스에만 적용되지만 어댑티 전체를 다시 구현하지 않아도 된다는 장점이 있다. 또한 서브클래스이기 때문에 어댑티의 행동을 오버라이드할 수 있다.
- 객체 어댑터는 유연성에 중점이 있고, 클래스 어댑터는 효율적이다.
- Java에서는 호환성이 없는 인터페이스를 가지고 있는 객체를 올바른 인터페이스를 구현하는 객체로 감싸서 어댑터 패턴을 구현할 수 있다.

# Facade Pattern

![Untitled](Head_First_Design_Pattern/Untitled%2040.png)

- 어떤 서브시스템의 일련의 인터페이스에 대한 통합된 인터페이스를 제공한다. 퍼사드에서 고수준 인터페이스를 정의하기 때문에 서브시스템을 더 쉽게 사용할 수 있다.
- 퍼사드 패턴을 사용하면 훨씬 쓰기 쉬운 인터페이스를 제공하는 퍼사드 클래스를 구현함으로써 복잡한 시스템을 훨씬 쉽게 사용할 수 있다.
- 퍼사드 패턴은 인터페이스를 단순화시키기 위해서 인터페이스를 변경한다. 하나 이상의 복잡한 인터페이스를 깔끔하면서도 말쑥한 퍼사드(겉모양, 외관)으로 덮어준다.
- 퍼사드 클래스에서는 서브시스템 클래스들을 캡슐화하지 않기에 서브시스템에 직접 접근할 수 있다. 퍼사드 클래스는 서브시스템의 기능을 사용할 수 있는 간단한 인터페이스를 제공한다.
- 즉, 단순화된 인터페이스를 제공하면서도, 클라이언트에서 필요로 한다면 시스템의 모든 기능을 사용할 수 있도록 해준다.
- 퍼사드 패턴에서 특정 서브시스템에 대해 만들 수 있는 퍼사드의 개수에는 제한이 없다.
- 퍼사드 패턴을 사용하면 클라이언트의 구현과 서브시스템을 분리시킬 수 있다.
- 어댑터 패턴과의 차이점은 클래스의 개수가 아닌 용도에 있다. 어댑터 패턴은 인터페이스를 변경해서 클라이언트에서 필요로 하는 인터페이스로 적용시키기 위한 용도로 사용된다. 퍼사드 패턴은 어떤 서브시스템에 대한 간단한 인터페이스를 제공하기 위한 용도로 사용된다.

![Untitled](Head_First_Design_Pattern/Untitled%2041.png)

- 퍼사드 패턴을 사용하려면 어떤 서브시스템에 속한 일련의 복잡한 클래스들을 단순화하고 통합한 클래스를 만들어야 한다.
- 퍼사드 패턴을 사용하면 클라이언트와 서브시스템이 서로 긴밀하게 연결되지 않아도 된다.

# Template Method Pattern

- 템플릿 메소드 패턴에서는 메소드에서 알고리즘의 골격을 정의한다. 알고리즘의 여러 단계 중 일부는 서브클래스에서 구현할 수 있다. 템플릿 메소드를 이용하면 알고리즘의 구조는 그대로 유지하면서 서브클래스에서 특정 단계를 재정의할 수 있다.

![Untitled](Head_First_Design_Pattern/Untitled%2042.png)

- 템플릿 메소드에서는 알고리즘의 각 단계들을 정의하며, 그 중 한 개 이상의 단계가 서브클래스에 의해 제공될 수 있다.
- 템플릿 메소드는 알고리즘을 틀을 만들기 위함에 그 목적이 있다. 틀(템플릿)이란 일련의 단계들로 알고리즘을 정의한 메소드이다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2043.png)
    

- 후크(hook)는 추상 클래스에서 선언되는 메소드지만 기본적인 내용만 구현되어 있거나 아무 코드도 들어있지 않은 메소드이다. 
→ 후크를 이용하면 서브클래스 입장에서는 다양한 위치에서 알고리즘에 끼어들 수 있다. 물론 무시하고 넘어갈 수도 있다.
- 서브클래스에서 알고리즘의 특정 단계를 제공해야만 하는 경우에는 추상 메소드를 써야 한다. 알고리즘의 특정 부분이 선택적으로 적용되는 경우에 후크를 사용한다.
- 템플릿 메소드에서 앞으로 일어날 일 또는 막 일어난 일에 대해 서브클래스에서 반응할 기회를 제공하기 위한 용도로도 후크를 사용한다.
- 템플릿 메소드를 만들 때는 추상 메소드가 너무 많아지지 않도록 주의해야 한다.
- sort() 메소드 구현은 교과서적이진 않지만, 템플리 메소드 패턴의 기본 정실을 충실하게 따르고 있다.
- JFrame은 가장 기본적인 스윙 컨테이너로 paint() 메소드를 상속받는 컨테이너다.
→ paint() 메소드는 후크 메소드. 오버라이드 하면 화면의 특정 영역에 어떤 내용을 표시하는 알고리즘에 원하는 그래픽을 추가할 수 있다.

![Untitled](Head_First_Design_Pattern/Untitled%2044.png)

![Untitled](Head_First_Design_Pattern/Untitled%2045.png)

- 템플릿 메소드 패턴은 서브클래스에서 일부 행동을 지정할 수 있게 해주면서도 코드를 재사용할 수 있게 해 주는 기본 메소드를 제공해준다.

# Iterator Pattern

- 이더레이터 패턴은 컬렉션 구현 방법을 노출시키지 않으면서도 그 집합채 안에 들어있는 모든 항목에 접근할 수 있게 해 주는 방법을 제공한다.
- 바뀌는 부분을 캡슐화하라.
- Iterator  패턴은 Iterator  인터페이스에 의존한다.
- 이더레이터 패턴을 이용하면 집합체 내에서 어떤 식으로 일이 처리되는지에 대해서 전혀 모르는 상태에서 그 안에 들어있는 모든 항목들에 대해서 반복작업을 수행할 수 있다.
- 이더레이터 패턴을 사용하면 컬렉션 객체 안에 들어있는 모든 항목에 접근하는 방식을 통일, 어떤 종류의 집합체에 대해서도 사용할 수 있는 다형적인 코드를 만들 수 있다.
- 클라이언트에서 next()를 호출해서 다음 항목을 가져오는, 클라이언트가 반복작업을 제어하는 것을 외부 반복자라고 한다.

![Untitled](Head_First_Design_Pattern/Untitled%2046.png)

- 내부 반복자는 반복자 자신에 의해서 제어된다. 그럴 경우에는 반복자가 다음 원소에 대해서 어떤 작업을 직접 처리하기 때문에 반복자한테 모든 원소에 대해서 어떤 일을 할 것인지 알려줘야 한다. 즉, 클라이언트가 반복자한테 어떤 작업을 넘겨줘야 한다.
- Iterator 의 구현 방식 :
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2047.png)
    

# Composite Pattern

- 컴포지트 패턴을 이용하면 객체들을 트리 구조로 구성하여 부분과 전체를 나타내는 게층구조로 만들 수 있다. 이 패턴을 이용하면 클라이언트에서 개별 객체와 다른 객체들로 구성된 복합 객체(composite)를 똑같은 방법으로 다룰 수 있다.
- 컴포지트 패턴을 활용하면 중첩되어 있는 메뉴 그룹과 메뉴 항목을 동일한 구조 내에서 처리할 수 있다.
- 컴포지트 패턴은 단일 역할 원칙을 깨는 대신 투명성을 확보하기 위한 패턴이다. 
→ 여기서 투명성(transparency)이란 어떤 원소가 복합 객체인지 잎 노드인지가 클라이언트 입장에서 투명하게 느껴지는가의 여부다.

![Untitled](Head_First_Design_Pattern/Untitled%2048.png)

- Tree 구조의 Composite Pattern
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2049.png)
    
- 또한, 메뉴와 메뉴 항목을 같은 구조에 집어 넣어서 부분-전체 계층구조(part-whole hierarchy)를 생성할 수 있다.
→ 부분(메뉴 및 메뉴 항목) 들이 모여있지만, 모든 것을 하나로 묶어서 전체로 다룰 수 있는 구조.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2050.png)
    
- 디자인 원칙에서 제시하는 가이드라인을 따르는 것이 좋긴 하지만, 때로는 원칙을 위배되는 방식으로 디자인해야 할 필요도 있다.
- 컴포지트 패턴은 부분~전체 관계를 가진 객체 컬렉션이 있고, 그 객체들을 모두 똑같은 방식으로 다루고 싶을 때 사용하는 패턴.
- 다른 구성요소를 포함하고 있는 구성요소가 복합 객체(composite object), 다른 구성요소를 포함하지 않는 구성요소를 잎 객체(leaf object)라고 부른다.
- 컴포지트 패턴의 가장 큰 장점은 클라이언트를 단순화시킬 수 있다는 점이다.

# State Pattern

- 스테이트 패턴을 이용하면 객체의 내부 상태가 바뀜에 따라서 객체의 행동을 바꿀 수 있다. 마치 객체의 클래스가 바뀌는 것과 같은 효과를 얻을 수 있다.
- 스테이트 패턴은 내부 상태를 바꿈으로서 객체에서 행동을 바꾸는 것을 도와준다.
- 스테이트 패턴은 상태를 별도의 클래스로 캡슐화한 다음 현재 상태를 나타내는 객체에게 행동을 위임한다.
- 따라서, 내부 상태가 바뀜에 따라 행동도 달라진다.
- 스테이트 패턴은 상태 객체에 일련의 행동이 캡슐화된다. 상황에 따라 Context 객체에서 여러 상태 객체 중 한 객체에게 모든 행동을 맡기고, 그 객체의 내부 상태에 따라 현재 상태를 나타내는 객체가 바뀌며, 그 결과로 Context 객체의 행동도 자연스럽게 바뀌게 된다. 즉, 클라이언트는 상태 객체에 대해 거의 아무것도 몰라도 된다.
- 반면 스트래티지 패턴은 일반적으로 클라이언트에서 Context 객체에게 어떤 전략 객체를 사용할지를 지정해 준다.  또한 스트래티지 패턴은 주로 실행 시에 전략 객체를 변경할 수 있는 유연서을 제공하기 위한 용도로 사용된다. 보통 가장 적합한 전략 객체를 선택해서 사용하게 된다.

![Untitled](Head_First_Design_Pattern/Untitled%2051.png)

![Untitled](Head_First_Design_Pattern/Untitled%2052.png)

# Proxy Pattern

- 어떤 객체에 대한 접근을 제어하기 위한 용도로 대리인이나 대변인에 해당하는 객체를 제공하는 패턴
- Proxy는 접근을 제어하고 관리한다. 자신이 대변하는 객체와 그 객체에 접근하고자 하는 클라이언트 사이에서 여러 가지 방식으로 작업을 처리한다.
- 원격 프록시는 원격 객체에 대한 로컬 대변자 역할을 한다. 
→ 원격 객체(remote object) : 다른 자바 가상 머신의 힙에서 살고 있는 객체(다른 주소 공간에서 돌아가고 있는 원격 객체)
→ 로컬 대변자(local representative) : 로컬 대변자의 어떤 메소드를 호출하면 다른 원격 객체에게 그 메소드 호출을 전달해주는 역할을 맡고 있는 객체
- 클라이언트 객체에서는 원격 객체의 메소드 호출을 하는 것처럼 행동한다. 하지만 실제로는 로컬 힙에 들어있는 프록시 객체의 메소드를 호출한다. 네트워크 통신과 관련된 저수준 작업은 이 프록시 객체에서 처리해 준다.
- RMI(Remote Method Invocation) : 원격 메소드 호출. 클라이언트 보조 객체(client helper, Stub)의 메소드를 통해 서버의 서비스 보조 객체(service helper, Skeleton)를 호출한다.

![Untitled](Head_First_Design_Pattern/Untitled%2053.png)

![Untitled](Head_First_Design_Pattern/Untitled%2054.png)

- RMI에서는 클라이언트와 서비스 보조 객체를 만들어 준다. 보조 객체에는 원격 서비스와 똑같은 메소드가 들어있다. 
→ RMI를 이용하면 네트워킹 및 입출력 관련 코드를 직접 작성하지 않아도 된다. 
→ 클라이언트에서는 그 클라이언트와 같은 로컬 JVM에 있는 메소드를 호출하듯이 원격 메소드(서비스 객체에 있는 메소드)를 호출할 수 있다.
→ 또한 클라이언트에서 원격 객체를 찾아서 그 원격 객체에 접근하기 위해 쓸 수 있는 룩업(lookup) 서비스와 같은 것도 RMI에서 제공해 준다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2055.png)
    
- RMI의 작동 순서는 다음과 같다. 
→ 클라이언트에서 RMI 레지스트리 룩업
→ RMI 레지스트리에서 스터브 객체를 리턴 
—> 스터브 객체는 lookup() 메소드의 리턴값으로 전달되며, RMI에서는 그 스터브를 자동으로 역직렬화한다. 이때 (rmic에서 생성해 준) 스터브 클래스가 반드시 클라이언트 쪽에 있어야 한다. 
→ 클라이언트에서 스터브에 대해 메소드를 호출한다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2056.png)
    
- 프록시에서 접근을 제어하는 주된 방법은 다음의 3가지다. 

→ 원격 프록시를 써서 원격 객체에 대한 접근을 제어할 수 있다.
→ 가상 프록시(virtual proxy)를 써서 생성하기 힘든 자원에 대한 접근을 제어한다.
→ 보호 프록시(protection proxy)를 써서 접근 권한이 필요한 자원에 대한 접근을 제어할 수 있다.
- 원격 프록시는 다른 JVM에 들어있는 객체의 대변인에 해당하는 로컬 객체다. 프록시의 메소드를 호출하면 그 호출이 네트워크를 통해서 전달되어 결국 원격 객체의 메소드가 호출된다. 그리고 그 결과는 다시 프록시를 거쳐서 클라이언트한테 전달된다.
- 가상 프록시는 생성하는데 많은 비용이 드는 객체를 대신하는 역할을 맡는다. 실제로 객체가 필요하게 되기 전까지 객체의 생성을 이루게 해 주는 기능을 제공하기도 한다. 객체 생성 전, 또는 객체 생성 도중에 객체를 대신하기도 한다.
- 보호 프록시(Protection Proxy)는 접근 권한(access right)을 바탕으로 객체에 대한 접근을 제어하는 프록시다.
- 방화벽 프록시는 일련의 네트워크 자원에 대한 접근을 제어함으로써 주 객체를 나쁜 클라이언트로부터 보호해 준다.
- 스마트 레퍼런스 프록시(Smart Reference Proxy)는 주 객체가 참조될 때마다 추가 행동을 제공한다. 객체에 대한 레퍼런스 개수를 세는 방식 등으로.
- 캐싱 프록시(Caching Proxy)는 비용이 많이 드는 작업의 결과를 임시로 저장해 준다. 여러 클라이언트에서 결과를 공유하게 해 줌으로써 계산 시간 또는 네트워크 지연을 줄여주는 효과도 있다.
- 동기화 프록시(Synchronization Proxy)에서는 여러 스레드에서 주 객체에 접근하는 경우에 안전하게 작업을 처리할 수 있게 해 준다.
- 복잡도 숨김 프록시(Complexity Hiding Proxy)에서는 복잡한 클래스들의 집합에 대한 접근을 제어하고, 그 복잡도를 숨겨준다. 퍼사드 프록시(Facade Proxy)라고 부르기도 한다.
- 지연 복사 프록시(Copy-On-Write Proxy) 에서는 클라이언트에서 필요로 할 때까지 객체가 복사되는 것을 지연시킴으로서 객체의 복사를 제어한다. 변형된 가상 프록시라고 할 수 있다.
- 동적 프록시의 동적은 클래스가 실행 중에 생성되기 때문이다. 실제로 코드가 실행되기 전까지는 프록시 클래스 자체가 없다. 전달 해 준 인터페이스를 바탕으로 즉석에서 클래스가 생성된다.

# Compound Pattern

- 컴파운드 패턴은 두 개 이상의 패턴을 결합하여 일반적으로 자주 등장하는 문제들에 대한 해법을 제공한다.
- 컴파운드 패턴은 일련의 패턴을 함께 사용하여 다양한 디자인 문제를 해결하는 것.
- 단, 패턴 몇 개를 결합해서 사용한다고 해서 컴파운드 패턴이 되는 것은 아니다. 여러 가지 문제를 해결하기 위한 용도로 쓰일 수 있는 일반적인 해결책이 핵심.
- 패턴은 반드시 상황에 맞게 사용해야 한다.
- Compound Pattern의 가장 대표적인 방식은 MVC Pattern. MVC Pattern은 Model, View, Controller의 계층으로 이루어져 있으며, 각 계층은 다음과 같다. 

→ 뷰 : 모델을 표현하는 방법을 제공한다. 일반적으로 화면에 표시하기 위해 필요한 상태 및 데이터는 모델에서 직접 가져온다. 

→ 컨트롤러 : 사용자로부터 입력을 받아서 그것이 모델에게 어떤 의미가 있는지 파악한다. 

→ 모델 : 모델에는 모든 데이터, 상태 및 애플리케이션 로직이 들어있다. 뷰와 컨트롤러가 모델의 상태를 조작하거나 가져오기 위한 인터페이스를 제공하고, 모델에서 자신의 상태 변화에 대해서 옵저버들에게 연락을 해주긴 하지만, 기본적으로 모델은 뷰 및 컨트롤러에 별 관심이 없다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2057.png)
    
- 클래스 다이어그램 :

![Untitled](Head_First_Design_Pattern/Untitled%2058.png)

![Untitled](Head_First_Design_Pattern/Untitled%2059.png)

![Untitled](Head_First_Design_Pattern/Untitled%2060.png)

- 뷰와 컨트롤러를 분리하는 이유는 다음과 같다. 

→ 두 계층을 통합시 뷰 코드가 지나치게 복잡해진다.
→ 뷰를 모델에 너무 밀접하게 연관시켜야 한다. (재사용성 감소, 느슨한 결합)
- 모델에서는 옵저버 패턴을 사용해서 상태가 바뀔 때마다 뷰와 컨트롤러한테 연락을 한다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2061.png)
    
- 뷰와 컨트롤러에서는 스트래티지 패턴을 사용한다. 컨트롤러는 뷰의 행동에 해당하며 다른 행동을 원한다면 다른 컨트롤러로 교환하면 된다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2062.png)
    
- 뷰 안에서는 내부적으로 컴포지트 패턴을 사용해서 윈도우, 버튼 같은 다양한 구성요소를 관리한다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2063.png)
    
- MVC MODEL2의 가장 큰 장점은 디자인적인 면에서 각 구성요소를 분리해주는 것에서 그치지 않고, 제작 책임까지도 분리시켜줄 수 있다는 점에 있다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2064.png)
    

# 그 밖의 패턴들

- Bridge Pattern
    - 구현 뿐만 아니라 추상화된 부분까지 변경시켜야 하는 경우에 사용한다.
    - 추상화된 부분과 추상 클래스/인터페이스를 구현한 클래스를 서로 다른 클래스 계층구조에 집어넣음으로써 그 둘을 모두 변경시킬 수 있다.
    - 장점 : 
    
    → 구현을 인터페이스에 완전히 결합시키지 않았기 때문에 구현과 추상화된 부분을 분리시킬 수 있다.
    → 추상화된 부분과 실제 구현 부분을 독립적으로 확장할 수 있다. 
    → 추상화된 부분을 구현한 구상 클래스를 바꿔도 클라이언트 쪽에는 영향을 끼치지 않는다.
    - 활용법 및 단점 :
    
     → 여러 플랫폼에서 사용해야 할 그래픽스 및 윈도우 처리 시스템에서 유용하게 사용된다.
    → 인터페이스와 실제 구현부를 서로 다른 방식으로 변경해야 하는 경우에 유용하게 쓰인다.
    → 디자인이 복잡해진다는 단점이 있다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2065.png)
    
- Builder Pattern
    - 제품을 여러 단계로 나눠서 만들 수 있도록 제품 생산 단계들을 캡슐화하고 싶을 때 사용한다.
    - 여행 계획표를 만드는 일을 객체(빌더)에 캡슐화시킴으로서 클라이언트에서는 빌더한테 여행 계획표 구조를 만들어달라고 요청하기만 하면 되도록 하는 것.
    - 장점 :
    
    → 복합 객체가 생성되는 과정을 캡슐화한다.
    → 여러 단계와 다양한 절차를 통해서 객체를 만들 수 있다. 
    → 제품의 내부 구조를 클라이언트로부터 보호할 수 있다. 
    → 클라이언트에서는 추상 인터페이스만 볼 수 있기에 제품을 구현한 코드를 쉽게 바꿀 수 있다.
    - 활용법 및 단점
    
    → 복합 객체 구조를 구축하기 위한 용도로 많이 사용된다. 
    → 팩토리를 사용하는 경우에 비해 객체를 만들기 위해서 클라이언트에 대해 더 많이 알아야 한다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2066.png)
    
- Chain of Responsibility Pattern
    - 한 요청을 두 개 이상의 객체에서 처리하고 싶을 때 사용한다.
    - 역할 사슬 패턴은 주어진 요청을 검토하기 위한 객체 사슬을 생성한다.
    - 사슬에 속해 있는 각 객체에서는 자기가 받은 요청을 검사하여 직접 처리하거나 사슬에 들어있는 다른 객체에게 넘긴다.
    - 장점 : 
    
    → 요청을 보낸 쪽하고 받는 쪽을 분리시킬 수 있다. 
    → 객체에서는 사슬의 구조를 몰라도 되고, 그 사슬에 들어있는 다른 객체에 대한 직접적인 레퍼런스를 가질 필요도 없기에 객체를 단순하게 만들 수 있다. 
    → 사슬에 들어가는 객체를 바꾸거나 순서를 바꿈으로써 역할을 동적으로 추가/제거 할 수 있다.
    - 사용법 및 단점 : 
    
    → 윈도우 시스템에서 마우스 클릭이나 키보드 이벤트를 처리할 때 흔하게 사용된다. 
    → 요청이 반드시 수행된다는 보장이 없다. 
    → 실행 시 과정을 살펴보거나 디버깅하기가 힘들다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2067.png)
    
- Flyweight Pattern
    - 어떤 클래스의 인스턴스 한 개만 가지고 여러 개의 가상 인스턴스를 제공하고 싶을 때 사용한다.
    - 플라이웨이트 패턴은 하나의 객체를 수 천개 만드는 대신, 객체의 인스턴스는 하나만 만들고 모든 객체의 상태를 클라이언트 객체에서 관리하도록 하는 것이다.
    - 장점 :
     
    → 실행시에 객체 인스턴스의 개수를 줄여서 메모리를 절약할 수 있다. 
    → 여러 가상 객체의 상태를 한 곳에 집중시킬 수 있다.
    
    - 사용법 및 단점 : 
    
    → 어떤 클래스의 인스턴스가 아주 많이 필요하지만 모두 똑같은 방식으로 제어할 수 있는 경우에 유용하다. 
    → 일단 플라이웨이트 패턴을 써서 구현하면 특정 인스턴스만 다른 인스턴스와 다른 식으로 행동하도록 하는 것이 불가능하다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2068.png)
    
- Interpreter Pattern
    - 어떤 언어에 대한 인터프리터를 만들 때 사용한다.
    - 간단한 언어를 구현해야 하는 경우, 인터프리터 패턴에서는 문법 및 그 구문을 번역하기 위한 인터프리터를 표현한 것을 클래스를 기반으로 정의한다.
    - 장점 : 
    
    → 각 문법 규칙을 클래스로 표현하기 때문에 언어를 쉽게 구현할 수 있다. 
    → 문법이 클래스에 의해 표현되기 때문에 언어를 쉽게 변경하거나 확장할 수 있다. 
    → 클래스 구조에 메소드만 추가하면 프로그램을 해석하는 기본 기능 외에 예쁘게 출력하는 기능, 더 나은 프로그램 확인 기능 같은 새로운 기능을 추가할 수 있다.
    
    - 활용법 및 단점 : 
    
    → 간단한 언어를 구현할 때 유용하게 사용된다. 
    → 문법이 간단학고 효율보다는 단순하게 만드는 것이 더 중요할 때 유용하다. 
    → 스크립트 언어 및 프로그래밍 언어에서 모두 사용할 수 있다.
    → 문법 규칙의 개수가 많아지면 아주 복잡해진다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2069.png)
    
- Mediator Pattern
    - 서로 관련된 객체 사이의 복잡한 통신과 제어를 한 곳으로 집중시키고자 하는 경우에 사용한다.
    - 상태가 바뀔 때마다 각 객체가 미디에이터에게 알리고, 미디에이터가 보낸 요청에 응답한다. 
    → 객체간의 응집도를 낮춘다.
    - 장점 : 
    
    → 시스템하고 각 객체를 분리시킴으로써 재사용성을 획기적으로 향상시킨다. 
    → 제어 로직을 한 곳에 모아놨기에 관리하기에 수월하다. 
    → 시스템에 들어있는 객체 사이에서 오가는 메시지의 종류를 줄이고 단순화시킬 수 있다.
    
    - 활용법 및 단점 : 
    
    → 서로 연관된 GUI 구성요소들을 관리하기 위한 용도로 많이 사용된다. 
    → 디자인을 잘 하지 못하면 미디에이터 객체 자체가 너무 복잡해진다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2070.png)
    
- Memento Pattern
    - 객체를 이전의 상태로 복구시켜야 할 때 사용된다. 
    → ex)  사용자의 작업 취소
    - 메멘터 패턴에는 두 가지 목적이 있다. 
    → 시스템에서 핵심적인 기능을 담당하는 객체의 중요한 상태 저장
    → 핵심적인 객체의 캡슐화 유지
    - 상태를 따로 저장하는 역할을 맡는 객체를 메멘토 객체라고 한다.
    - 장점 : 
    
    → 저장된 상태를 핵심 객체와는 다른 별도의 객체에 보관하기에 안전하다.
    → 핵심 객체의 데이터를 계속해서 캡슐화된 상태로 유지할 수 있다. 
    → 복구 기능을 구현하기 쉽다.
    
    - 활용법 및 단점 
    
    → 메멘토 객체를 써서 상태를 저장한다.
    → 상태를 저장하고 복구하는데 시간이 오래 걸린다. 
    → 자바 시스템에서는 시스템의 상태를 저장할 때 직렬화가 권장된다.
        
        ![Untitled](Head_First_Design_Pattern/Untitled%2071.png)
        
- Prototype Pattern
    - 어떤 클래스의 인스턴스를 만드는 것이 자원/시간을 많이 잡아먹거나 복잡한 경우에 사용된다.
    - 프로토타입 패턴을 이용하면 기존 인스턴스를 복사하기만 하면 새로운 인스턴스를 만들 수 있다.
    - 프로토타입의 가장 큰 특징은 클라이언트 코드에서 어떤 클래스의 인스턴스를 만드는지 전혀 모르는 상태에서도 새로운 인스턴스를 만들 수 있다는 점이다.
    - 장점 : 
    
    → 클라이언트에서는 새로운 인스턴스를 만드는 복잡한 과정을 몰라도 된다. 
    → 클라이언트에서는 구체적인 형식을 모르더라도 객체를 생성할 수 있다.
    → 상황에 따라서 객체를 새로 생성하는 것보다 객체를 복사하는 것이 더 효율적일 수 있다.
    
    - 활용법 및 단점 : 
    
    → 시스템에서 복잡한 클래스 계층구조에 파묻혀 있는 다양한 형식의 객체 인스턴스를 새로 만들어야 하는 경우에 유용하게 써먹을 수 있다. 
    → 때때로 객체의 복사본을 만드는 일이 매우 복잡한 경우가 있다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2072.png)
    
- Visitor Pattern
    - 다양한 객체에 새로운 기능을 추가해야 하는데 캡슐화가 별로 중요하지 않은 경우 사용된다.
    - 비지터 객체는 트래버서(Traverser) 객체하고 함께 돌아간다. 트래버서는 컴포지트 패턴을 쓰는 경우에 복합 객체 내에 속해 있는 모든 객체들에 접근하는 것을 도와주는 역할을 한다.
    - 장점 : 
    
    → 구조 자체를 변경시키지 않으면서도 복합 객체 구조에 새로운 기능을 추가할 수 있다.
    → 비교적 손쉽게 새로운 기능을 추가할 수 있다.
    → 비지터에서 수행하는 기능과 관련된 코드를 한 곳에 집중시켜 놓을 수 있다.
    - 단점 : 
    
    → 비지터를 사용하면 복합 클래스의 캡슐화가 깨진다.
    → 컬랙션 내의 모든 항목을 접근하기 위한 트래버서가 있기 때문에 복합 구조를 변경하기가 더 어려워진다.
    
    ![Untitled](Head_First_Design_Pattern/Untitled%2073.png)