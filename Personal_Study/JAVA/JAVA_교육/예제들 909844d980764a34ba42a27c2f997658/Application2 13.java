package com.greedy.section05.calendar;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

public class Application2 {
	public static void main(String[] args) {

		/*
		 * Calendar 클래스 사용
		 * API 문서를 보면 Calendar 클래스는 abstract 클래스로 작성되어 있다.
		 * 따라서 Calendar 클래스를 이용해서 인스턴스를 생성하는 것이 불가능하다.
		 */
		
		/* 1. getInstance() 메소드를 사용(싱글톤 패턴 적용) */
		Calendar calendar = Calendar.getInstance();
		System.out.println(calendar);
		
		/* 2. GregorianCalendar 활용 */
		Calendar gregorianCalendar = new GregorianCalendar();
		System.out.println(gregorianCalendar);
		
		/* 2014년 9월 18일 16:42:20 */
		int year = 2014;
		int month = 9 -1;
		int dayOfMonth = 18;
		int hour = 16;
		int min = 42;
		int second = 20;
		
		Calendar birthDay =
				new GregorianCalendar(year, month, dayOfMonth, hour, min, second);
		System.out.println(birthDay);
		
		int birthYear = birthDay.get(GregorianCalendar.YEAR);
		int birthMonth = birthDay.get(Calendar.MONTH);
		int birthDayOfMonth = birthDay.get(Calendar.DATE);

		System.out.println(birthYear);
		System.out.println(birthMonth + 1);				// get을 통해 빼낸 월은 +1을 해줘야 원하는 월이 된다. 
		System.out.println(birthDayOfMonth);

		/* 날짜와 관련된 클래스들은 내부적으로 월을 0 ~ 11로 계산하므로 사용 시에 주의하자!! */
		
		/* 요일 처리 */
		/* 요일(일(1), 월(2), 화(3), 수(4), 목(5), 금(6), 토(7)) */
		System.out.println("dayOfWeek: " + birthDay.get(Calendar.DAY_OF_WEEK));
		
		String day = "";
		switch(birthDay.get(Calendar.DAY_OF_WEEK)) {
		case 1 : day = "일"; break;
		case 2 : day = "월"; break;
		case 3 : day = "화"; break;
		case 4 : day = "수"; break;
		case 5 : day = "목"; break;
		case 6 : day = "금"; break;
		case 7 : day = "토"; break;
		}
		System.out.println("나는 " + day + "요일에 태어났어!");
		
		/* 추가적인 내용 */
		System.out.println("am, pm: " + birthDay.get(Calendar.AM_PM)); 			 // 0은 오전, 1은 오후
		System.out.println(birthDay.get(Calendar.AM_PM) == 0 ? "오전" : "오후");
		
		System.out.println("hourOfDay: " + birthDay.get(Calendar.HOUR_OF_DAY));  // 24시간 체제
		System.out.println("hour: " + birthDay.get(Calendar.HOUR));  			 // 12시간 체제
		
		System.out.println("min: " + birthDay.get(Calendar.MINUTE));
		System.out.println("second: " + birthDay.get(Calendar.SECOND));
		
		/* simpleDateFormat 활용하기 */
		SimpleDateFormat sdf = new SimpleDateFormat("a yyyy-MM-dd hh:mm:ss E요일 완전 조아");
		String formatDate = sdf.format(new Date(birthDay.getTimeInMillis()));
		System.out.println(formatDate);

		/*
		 * 윤년이란? 년도가(4의 배수이면서, 100의 배수가 아니거나, 400의 배수가) 되는 해가 윤년
		 * 				(1년을 366일로 계산(=2월이 29일인 해))
		 * 
		 * 율리우스력의 근소한 오차 값을 수정한 그레고리력
		 * (1년을 365 1/4일(365.2422일))
		 * 1) 그 해의 연도가 4의 배수가 아니면 평년으로 2월은 28일만 있다.
		 * 2) 만약 연도가 4의 배수이면서 100의 배수가 아니면 윤일(2월 29일)을 도입한다.
		 * 3) 만약 연도가 100의 배수이면서 400의 배수가 아닐 때 이 해는 평년으로 생각한다.
		 * 4) 만약 연도가 400의 배수면 윤일(2월 29일)을 도입한다. (이거만 맞으면 윤년)
		 * 
		 * 2008년 - 윤년 366일
		 * 
		 * 2300년 - 평년 365일
		 * 
		 * 2400년 -  윤년 366일
		 */

		int i = 2400;
		if(i%400 ==0) {
			System.out.println("윤년");
		} else if(i%4 == 0 && i%100 !=0) {
			System.out.println("윤년");;
		} else System.out.println("평년");
		
		
		
	}

/*
 *  Date -> 메소드 종류가 많다. 
 *  getYear()
 *  getMonth() 등등
 *  Calendar -> 메소드명이 하나뿐이다.
 *  get(1);
 *  get(3); 식으로 사용
 *  Date -> Calendar 는 상수필드와 타임존이 추가되었다.
 *  GregorianCalendar 는 윤달의 개념이 추가.
 *  단, 달은 1~12월이 아닌, 0~11월을 사용한다.
 */
}


