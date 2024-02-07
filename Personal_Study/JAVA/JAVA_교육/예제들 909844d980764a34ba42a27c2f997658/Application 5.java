package com.greedy.section01.user_type;

import java.util.Arrays;

public class Application {
	public static void main(String[] args) {
		
		/* 지금까지 자바에서 제공되는 자료들을 취급하는 자료형에 대해 학습했다. (변수, 배열) */
		/*
		 * 이제 조금 더 복잡한 자료를 취급할 수 있는 방법을 생각해 보자.
		 * 회원 정보를 관리하기 위해 회원의 여러 정보(아이디, 비밀번호, 이름, 나이, 성별, 취미)를 
		 * 취급하여 지금까지 배운 내용을 가지고 한 번 값을 저장하고 출력해 보자.
		 */
		
		String id = "user01";
		String pwd = "pass01";
		String name = "홍길동";
		int age = 20;
		char gender = '남';
		String[] hobby = new String[] {"축구", "볼링", "테니스"};
		
		System.out.println("id : " + id);
		System.out.println("pwd : " + pwd);
		System.out.println("name : " + name);
		System.out.println("age : " + age);
		System.out.println("gender : " + gender);
		System.out.println("hobby : " + Arrays.toString(hobby));
		
		/*
		 * 이렇게 각각의 변수로 관리하게 되면 여러가지 단점이 있다.
		 * 1. 변수명을 다 관리해야 하는 어려움이 생긴다. (회원 수가 늘어날수록 어려움이 커진다.)
		 * 2. 모든 회원 정보를 인자로 메소드 호출 시 값을 전달해야 하면 너무 많은 값들을 인자로 전달해야 한다.
		 * 3. 리턴은 1가지 자료형의 값만 가능하기 때문에 회원 정보를 묶어서 리턴값으로 사용할 수 없다.
		 * 		(서로 다른 자료형이기 때문에)
		 */
		
		System.out.println(returnString(id, pwd, name, age, gender, hobby));
		
		/* 1. 변수 선언 및 객체(인스턴스) 생성 */
//		int[] iArr = new int[5];
		Member m = new Member();				// heap 영역에 Member의 인스턴스를 올린다.
		
		int age2 = returnMember(m).age;
		
		System.out.println(m.id);
		System.out.println(m.pwd);		//heap 영역에 생성 되므로 jvm이 기본값으로 초기화 된다.
		System.out.println(m.age);		//heap 영역에 생성 되므로 jvm이 기본값으로 초기화 된다.

		/* 2. 필드에 접근해서 변수 사용하듯이 사용할 수 있다. */
		m.id = "u01";
		m.pwd = "p01";
		m.name = "홍길동";
		m.age = 20;
		m.gender = '남';
		m.hobby = new String[] {"축구", "볼링", "테니스"};
		
		System.out.println("메소드를 호출하는 곳에서 출력: " + returnMember(m).id);
		
		
		
		
	}

	

	
	/* 회원 한명의 정보를 넘겨주면 하나의 문자열로 반환해 주는 메소드 */
	public static String returnString(String id, String pwd, String name, int age, 
										char gender, String[] bobby) {
		return id + pwd + name + age + gender +Arrays.toString(bobby);
	}
	
	public static Member returnMember(Member m) {
		return m;
	}
}
