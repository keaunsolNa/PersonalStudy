package com.greedy.section04.constructor;

import com.greedy.section04.constructor.dto.UserDTO2;

public class Application2 {

	public static void main(String[] args) {

		/* 설정자를 이용한 초기화와 생성자를 이용한 초기화는 각각의 장단점이 존재한다. */
		/*
		 * 1. 기본생성자와 설정자를 이용한 초기화
		 * 장점: 필드를 초기화 하는 각각의 값들이 어떤 필드를 초기화 하는지 명확하게 쓸 수 있다.
		 * 단점: 하나의 인스턴스를 생성할 때 한번의 호출로 끝나지 않는다.
		 * 		(필드 변수의 갯수에 따라 호출 횟수가 증가함)
		 * 
		 * 2. 매개변수 있는 생성자를 이용한 초기화
		 * 장점 : setter메소드를 여러번 호출해서 사용하지 않고 단 한번의 호출로 인스턴스를 생성 및 필드값
		 * 		 초기화가 가능하다.
		 * 단점 : 필드를 초기화 할 매개변수의 갯수에 따라 경우의 수 별로 생성자를 모두 만들어 주어야 한다.
		 * 		 호출 시 전달인자가 많아지는 경우 어떠한 값이 어떤 필드를 의미하는지 한 눈에 보기 어렵다.
		 */
		
		/* 기본 생성자와 설정자(setter)를 이용한 초기화(단축키로 만든 UserDTO2를 활용) */
		UserDTO2 user = new UserDTO2();
		user.setId("user01");
		user.setPwd("pwd01");
		user.setName("greedy");
		user.setEnrollDate(new java.util.Date());
		System.out.println(user.toString());
		
		/* 매개변수 있는 생성자를 이용한 초기화 */
		UserDTO2 user2 = new UserDTO2("user02", "pwd02", "himedia", new java.util.Date());
		System.out.println(user2.toString());
	}
}
