package com.greedy.section05.calendar;

import java.util.Date;

public class Application1 {
	public static void main(String[] args) {

		/* Date 클래스 */
		/*
		 * JDK 1.0부터 날짜를 취급하기 위해 사용되던 Date 클래스는
		 * 생성자를 비롯해서 대부분의 메소드가 Deprecated 되어 있다.
		 * 
		 * Deprecated란?
		 * 향후 버전이 업데이트 되면서 사라지게 될 기능이니 가급적이면 사용을 권장하지 않는다는 의미이다.
		 * 하지만 하위 버전의 호환성 때문에 한번에 제거된 것은 아니고 남겨두었기 때문에 사용하는 것은
		 * 가능하다.
		 * 
		 * Date는 java.sql.Date와 java.util.Date가 존재한다.
		 * 한 클래스에서 두 개의 타입을 전부 사용하게 되면 import를 하더라도 사용하는 타입이
		 * 어느 패키지에 있는 Date클래스인지에 대한 모호성이 발생하게 된다.
		 * 따라서 import를 해도 풀 클래스명으로 작성해 주는 것이 좋다.
		 * ex) (java.sql.Date를 import 했을 경우)
		 * 		java.util.Date d = new java.util.Date();
		 * 		Date d1 = new Date(123L);
		 * 
		 * 우리는 자바를 배우는 동안에는 java.util.Date만 사용할 것이다.
		 */
		
		/* 1. 기본생성자 활용 */
		/*
		 * 기본생성자로 인스턴스를 생성하면 개발중인 컴퓨터의 운영체제 날짜/시간 정보를 이용해서 인스턴스를
		 * 만들게 된다. (시스템 시간)
		 */
		Date today = new Date();
		
		/* toString() 메소드가 오버라이딩 되어 있어서 쉽게 필드값(시간정보)를 출력해 볼 수 있다. */
		System.out.println(today.toString());
		
		/* 2. Date(long date) 생성자 활용 */
		/*
		 * gettime() : 1970년 1월 1일 0시 0분 0초 이후 지난 시간을 millisecond로 계산해서
		 * 			   long 타입으로 반환하는 메소드이다.
		 */
		System.out.println(today.getTime());
		
		Date time = new Date(1646617575117L);
		System.out.println(time);
		
		Date defualtTime = new Date(0);
		System.out.println(defualtTime);
		
		/* 하루가 몇 milliseconds 일까? */
		/*
		 * 하루 = 24시간, 1시간 = 60분, 1분 = 60초, 1초 = 1000밀리초
		 * 1000 * 60 * 60 * 24 밀리초가 하루가 된다.
		 */
	}

}
