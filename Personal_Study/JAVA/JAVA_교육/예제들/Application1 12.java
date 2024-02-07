package com.greedy.section02.string;

import java.util.Scanner;

public class Application1 {

	public static void main(String[] args) {

		/* String 클래스의 자주 사용하는 메소드들 */
		
		/*
		 * charAt() : 해당 문자열의 특정 인덱스에 해당하는 문자를 char형으로 반환한다
		 * 인덱스는 0부터 시작하는 숫자체계를 의미하며
		 * 인덱스를 벗어난 정수를 인자로 전달하는 경우에는 IndexOutOfBoundsException이 발생한다.
		 */
		String str1 = "apple";
		
		for(int i = 0; i < str1.length(); i++) {
			System.out.println("charAt(" + i + ") : " + str1.charAt(i));
		}
		/*
		 * compareTo() : 인자로 전달된 문자열과 사전 순으로 비교를 하며
		 * 두 문자열이 같다면 0을 반환, 인자로 전달된 문자열보다 작으면 음수를,
		 * 크면 양수를 반환한다.
		 * 단, 이 메소드는 대소문자를 구분하여 비교한다.
		 */
		String str2 = "java";
		String str3 = "java";
		String str4 = "JAVA";
		String str5 = "oralce";

		/* 같은 문자열이면 0을 반환한다. */
		System.out.println("compare To() : " + (str2.compareTo(str3)));			// 0
		
		/* 대문자와 소문자는 32만큼 차이가 난다. */
		int ch1 = 'j';
		int ch2 = 'J';
		System.out.println("ch1: " + ch1 + ", ch2: " + ch2);
		System.out.println("compare To() : " + (str2.compareTo(str4)));			// 32 ('j' - 'J')
		System.out.println("compare To() : " + (str4.compareTo(str2)));			// -32

		/* jklmno j부터 o까지 5만큼 차이가 난다. */
		System.out.println("compare To() : " + (str2.compareTo(str5)));			// -5
		System.out.println("compare To() : " + (str5.compareTo(str2)));			// 5
	
		/* 구체적으로 어떤 값이 나오는지보다 양수인지 음수인지 0인지를 판단할 목적으로 주로 사용된다. */
		
		/* compareToIgnoreCase() : 대소문자를 구분하지 않고 비교한다. */
		System.out.println("compareToIgnoreCase(): " + str3.compareToIgnoreCase(str4));
	
		/*
		 * concat() : 문자열에 인자로 전달된 문자열을 합치기해서 새로운 문자열을 반환한다.
		 *			  원본에는 영향을 주지 않는다.
		 */
		System.out.println("concat(): " + str2.concat(str5));
		System.out.println("str2(원본): " + str2);
		
		/*
		 * indexOf() : 문자열에서 특정 문자를 탐색하여 처음 일치하는 인덱스 위치를 정수형으로 반환한다.
		 * 			   단, 일치하는 문자가 없을 경우 -1을 반환한다.
		 */
		String indexOf = "java oracle";

		System.out.println("indexOf('a'): " + indexOf.indexOf('a'));
		System.out.println("indexOf('a'): " + indexOf.indexOf('z'));

		/*
		 * lastIndexOf() : 문자열 탐색을 뒤에서부터 하고 처음 일치하는 위치의 인덱스를 반환한다.
		 * 					단, 일치하는 문자가 없는 경우 -1을 반환한다.
		 */
		System.out.println("lastIndexOf('a'): " + indexOf.lastIndexOf('a'));
		System.out.println("lastIndexOf('a'): " + indexOf.lastIndexOf('z'));
		
		/* trim() : 문자열의 앞 뒤에 공백을 제거한 문자열을 반환한다. */
		String trimStr = "            java                ";
		System.out.println("trimStr: #" + trimStr + "#");
		System.out.println("trimStr: #" + trimStr.trim() + "#");
		
		/*
		 * toLowerCase() : 모든 문자를 소문자로 변환시킨다.
		 * toUpperCase() : 모든 문자를 대문자로 변환시킨다.
		 * 원본에는 영향을 주지 않는다.
		 */
		String caseStr = "JavaOracle";
		System.out.println("toLowerCase(): " + caseStr.toLowerCase());
		System.out.println("toUpperCase(): " + caseStr.toUpperCase());
		
		/* toLowerCase()나 toUpperCase()를 사용하는 예시 */
//		Scanner sc = new Scanner(System.in);
//		System.out.println("프로그램을 종료 하려면 'y'를 입력하세요: ");
//		char ans = sc.nextLine().charAt(0);
//		String strAns = ans + "";						// char -> String
//		
//		if(strAns.toLowerCase().equals("y")) {
//			System.out.println("프로그램을 종료합니다.");
//		} else {
//			System.out.println("y를 누르지 않았네요");
//		}
		
		/*
		 * substring() : 문자열의 일부분을 잘라내어 새로운 문자열을 반환한다.
		 * 				 원본에는 영향을 주지 않는다.
		 */
		String javaoracle = "javaoracle";
		
		System.out.println(javaoracle.substring(4,10));
		System.out.println(javaoracle.substring(4));
		
		/*
		 * replace() : 문자열에서 대체할 문자열로 기존 문자열을 변경해서 반환한다.
		 * 			   원본에는 영향을 주지 않는다.
		 */
		System.out.println("replace(): " + javaoracle.replace("java", "python"));
		
		/* length() : 문자열의 길이를 정수형으로 반환한다. */
		System.out.println("length(): " + javaoracle.length());
		System.out.println("빈 문자열 길이: " + "".length());
		
		/*
		 * isEmpty() : 문자열의 길이가 0이면 true를 반환, 아니면 false를 반환
		 * 			   길이가 0인 문자열은 null과는 다르다.
		 */
		System.out.println("isEmpty(): " +"".isEmpty());
		System.out.println("isEmpty(): " + "abc".isEmpty());
	}
}
