package com.greedy.section06.singleton;

import java.util.Calendar;

public class Application {

	public static void main(String[] args) {
		
		/* static을 이용한 싱글톤 패턴 */
		/*
		 * singleton pattern이란
		 * 애플리케이션이 시작될 때 어떤 클래스가 최초 한번만 메모리에 할당되고
		 * 그 메모리에 인스턴스를 만들어서 하나의 인스턴스를 공유해서 사용하며
		 * 메모리 낭비를 방지할 수 있게 하고자 하는 패턴(매번 인스턴스를 생성하지 않음)
		 * 
		 * 장점
		 * 1. 첫 번째 이용 시에는 인스턴스를 생성해야 하므로 속도 차이가 나지 않지만
		 *    두 번째 이용 시에는 인스턴스 생성 시간 없이 사용할 수 있다.
		 * 2. 인스턴스가 절대적으로 한 개만 존재하는 것을 보증할 수 있다.
		 * 
		 * 단점
		 * 1. 싱글톤 인스턴스가 너무 많은 일을 하거나 많은 데이터를 공유하면 결합도가 높아진다.
		 *    (유지보수와 테스트에 문제점이 있음)
		 * 2. 동시성 문제를 고려해서 설계해야 하기 때문에 난이도가 있다.
		 */
		
		/*
		 * 싱글톤 구현 방법
		 * 1. 이른 초기화	(Eager Initialization)
		 * 2. 게으른 초기화 (Lazy Initialization)
		 */
		
		/* 1. 이른 초기화 구현 */
//		EagerSingleton eager = new EagerSingleton();	// 생성자가 private라 접근 불가하고 인스턴스 생성 x 
		
		/* getInstance()라는 static 메소드를 호출해야만 인스턴스를 활용할 수 있다. */
		EagerSingleton eager1 = EagerSingleton.getInstance();
		EagerSingleton eager2 = EagerSingleton.getInstance();
		
		/* 두 인스턴스의 hashCode가 동일한지 확인해 보자. (동일한 인스턴스인지) */
		System.out.println("eager1의 hashCode : " + eager1.hashCode());
		System.out.println("eager2의 hashCode : " + eager2.hashCode());
		
		/* 2. 게으른 초기화 구현 */
		LazySingleton lazy1 = LazySingleton.getInstance();
		LazySingleton lazy2 = LazySingleton.getInstance();
		
		/* 두 인스턴스의 hashCode가 동일한지 확인해 보자. (동일한 인스턴스인지) */
		System.out.println("lazy1의 hashCode : " + lazy1.hashCode());
		System.out.println("lazy2의 hashCode : " + lazy2.hashCode());
		
		/*
		 * 이른 초기화를 사용하면 클래스를 로드하는 속도가 느려지지만
		 * 처음 인스턴스 반환 요청에서 속도가 빠르다는 장점을 가진다.
		 * 반면 게으른 초기화는 클래스를 로드하는 속도는 빠르지만
		 * 첫 번째 요청에 대한 속도가 느리다는 특징을 가지고 있다.
		 */
		
		/* 결론은 싱글톤은 하나의 인스턴스를 공유할 목적으로 만들어진 패턴이라는 것을 이해하자. */
		
		/* 싱글톤 패턴을 느낄 수 있는 API 클래스 */
		Calendar cal = Calendar.getInstance();
		
		
	}
}






